"""Failure injection exercises the data boundary, not just happy-path commands."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import urllib.error
from unittest.mock import patch, MagicMock

from mojie_deploy import Deployment, DockerDriver, deployment_lock, read_json, validate_release, validate_initial_storage, write_json


RELEASE = {
    'schemaVersion': 1, 'deploymentContract': 2, 'repository': 'owner/mojie',
    'sourceRef': 'refs/heads/main', 'commit': 'a' * 40,
    'runId': 42, 'runNumber': 2, 'runAttempt': 1,
    'images': {name: f'ghcr.io/owner/mojie-{name}@sha256:' + 'b' * 64 for name in ('backend', 'web')},
}


class FakeDriver:
    def __init__(self, failure=None):
        self.failure = failure
        self.calls = []
        self.content = {'old': {'chapter': '作者的正文'}, 'new': {}}
        self.running = {'old'}
        self.active = False
        self.upstream = 'old'

    def step(self, name):
        self.calls.append(name)
        if name == self.failure:
            raise RuntimeError('Injected failure at ' + name)

    def gate(self, active):
        self.active = active
        self.step('gate-on' if active else 'gate-off')

    def prepare(self, release, current):
        self.step('prepare')
        return {'project': 'new', 'port': 18089, 'directory': 'new'}

    def drain(self, current): self.step('drain')
    def freeze(self, current): self.step('freeze'); self.running.discard('old')
    def fingerprint(self, current): return copy.deepcopy(self.content[current['project']])
    def backup(self, current): self.step('backup'); return 'backup'
    def stop(self, record):
        self.step('stop-' + record['project'])
        self.running.discard(record['project'])
    def restore(self, candidate, backup):
        self.content['new'] = copy.deepcopy(self.content['old'])
        self.step('restore')
    def start(self, record):
        self.running.add(record['project'])
        if record['project'] == 'new':
            self.content['new']['migration'] = 'new schema'
        self.step('start-' + record['project'])
    def resume(self, record): self.running.add(record['project']); self.step('resume-' + record['project'])
    def verify(self, record, expected=None): self.step('verify-' + record['project'])
    def verify_files(self, record, backup, after_start=False): self.step('files-' + record['project'])
    def switch(self, record): self.upstream = record['project']; self.step('switch-' + record['project'])
    def verify_gateway(self, record): self.step('gateway-' + record['project'])


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.current = {'project': 'old', 'port': 18088, 'directory': 'old'}
        write_json(self.root / 'state.json', {'phase': 'COMMITTED', 'current': self.current,
                   'currentRunNumber': 1, 'failedReleases': []})

    def tearDown(self): self.temporary.cleanup()

    def test_every_precommit_failure_returns_to_unchanged_old_data(self):
        for phase in ('drain', 'freeze', 'backup', 'restore', 'start-new', 'verify-new', 'files-new', 'switch-new', 'gateway-new'):
            with self.subTest(phase=phase):
                self.setUpState()
                driver = FakeDriver(phase)
                with self.assertRaises(RuntimeError): Deployment(self.root, driver).deploy(RELEASE)
                state = read_json(self.root / 'state.json')
                self.assertEqual('ROLLED_BACK', state['phase'])
                self.assertEqual({'chapter': '作者的正文'}, driver.content['old'])
                self.assertEqual({'old'}, driver.running)
                self.assertEqual('old', driver.upstream)
                self.assertFalse(driver.active)
                self.assertIn('42-1', state['failedReleases'])

    def setUpState(self):
        write_json(self.root / 'state.json', {'phase': 'COMMITTED', 'current': self.current,
                   'currentRunNumber': 1, 'failedReleases': []})

    def test_success_retains_old_data_and_commits_before_unlock(self):
        driver = FakeDriver()
        deployment = Deployment(self.root, driver)
        original = driver.gate
        def gate(active):
            if not active:
                self.assertEqual('COMMITTED', read_json(deployment.state_path)['phase'])
                self.assertEqual('new', read_json(deployment.state_path)['current']['project'])
            original(active)
        driver.gate = gate
        deployment.deploy(RELEASE)
        self.assertEqual({'new'}, driver.running)
        self.assertEqual({'chapter': '作者的正文'}, driver.content['old'])
        self.assertEqual('new', driver.upstream)

    def test_crash_at_each_precommit_phase_is_recovered_to_old(self):
        for phase in ('PREPARED', 'DRAINING', 'FROZEN', 'CANDIDATE_READY', 'SWITCHED', 'ROLLING_BACK'):
            with self.subTest(phase=phase):
                state = {'phase': phase, 'current': self.current, 'previous': self.current,
                         'candidate': {'project': 'new'}, 'pendingRelease': RELEASE}
                write_json(self.root / 'state.json', state)
                driver = FakeDriver()
                driver.running = {'new'}
                Deployment(self.root, driver).recover()
                self.assertEqual({'old'}, driver.running)
                self.assertEqual('ROLLED_BACK', read_json(self.root / 'state.json')['phase'])

    def test_committed_recovery_preserves_new_user_writes(self):
        driver = FakeDriver()
        deployment = Deployment(self.root, driver)
        deployment.deploy(RELEASE)
        driver.content['new']['chapter'] = '发布后新保存的正文'
        deployment.recover()
        self.assertEqual('发布后新保存的正文', driver.content['new']['chapter'])
        self.assertNotIn('resume-old', driver.calls)
        self.assertEqual('new', driver.upstream)

    def test_unlock_failure_after_commit_never_restores_old_data(self):
        driver = FakeDriver('gate-off')
        with self.assertRaises(RuntimeError): Deployment(self.root, driver).deploy(RELEASE)
        self.assertEqual('COMMITTED', read_json(self.root / 'state.json')['phase'])
        self.assertTrue(driver.active)
        self.assertNotIn('resume-old', driver.calls)

    def test_rollback_stop_failure_keeps_maintenance_and_does_not_start_old(self):
        driver = FakeDriver('stop-new')
        deployment = Deployment(self.root, driver)
        state = {'phase': 'FROZEN', 'previous': self.current, 'candidate': {'project': 'new'}, 'pendingRelease': RELEASE}
        with self.assertRaises(RuntimeError): deployment.rollback(state)
        self.assertTrue(driver.active)
        self.assertNotIn('resume-old', driver.calls)

    def test_replay_failed_release_and_concurrent_deploy_are_rejected(self):
        driver = FakeDriver()
        deployment = Deployment(self.root, driver)
        bad = dict(RELEASE, runNumber=1)
        with self.assertRaises(RuntimeError): deployment.deploy(bad)
        state = read_json(deployment.state_path)
        state['failedReleases'] = ['42-1']
        write_json(deployment.state_path, state)
        with self.assertRaises(RuntimeError): deployment.deploy(RELEASE)
        with deployment_lock(self.root):
            with self.assertRaises(BlockingIOError):
                with deployment_lock(self.root): pass
        self.assertEqual([], driver.calls)

    def test_manifest_rejects_mutable_images_other_repositories_and_partial_builds(self):
        self.assertEqual(RELEASE, validate_release(copy.deepcopy(RELEASE), 'owner/mojie'))
        for field, value in (('repository', 'attacker/mojie'), ('sourceRef', 'refs/pull/1/merge'), ('deploymentContract', 3)):
            bad = dict(RELEASE, **{field: value})
            with self.assertRaises(ValueError): validate_release(bad, 'owner/mojie')
        for reference in ('ghcr.io/owner/mojie-web:latest', 'ghcr.io/attacker/mojie-web@sha256:' + 'b' * 64):
            bad = copy.deepcopy(RELEASE)
            bad['images']['web'] = reference
            with self.assertRaises(ValueError): validate_release(bad, 'owner/mojie')
        bad = copy.deepcopy(RELEASE)
        del bad['images']['backend']
        with self.assertRaises(ValueError): validate_release(bad, 'owner/mojie')

    def test_candidate_does_not_share_resolved_network_names_or_mutate_host_config(self):
        config = {'root': str(self.root)}
        driver = DockerDriver(config)
        spec = {'name': 'live', 'volumes': {'mojie_files': {'name': 'live_files'}},
                'networks': {'default': {'name': 'live_default'}, 'execution': {'name': 'live_execution', 'internal': True}},
                'services': {'postgres': {}, 'files-init': {}, 'backend': {'environment': {}, 'volumes': []},
                             'nginx': {}, 'skill-executor': {'environment': {}, 'volumes': [{'target': '/var/run/docker.sock'}]}}}
        saved = copy.deepcopy(spec)
        legacy_images = {**RELEASE['images'], 'executor': 'ghcr.io/owner/mojie-executor@sha256:' + 'b' * 64}
        legacy = dict(RELEASE, deploymentContract=1, images=legacy_images)
        validate_release(legacy, 'owner/mojie')
        candidate = driver.make_record(spec, 'candidate', 18089, legacy_images, 'test')
        output = read_json(candidate['compose'])
        self.assertEqual(spec, saved)
        self.assertNotIn('name', output['networks']['default'])
        self.assertTrue(output['networks']['execution']['internal'])
        self.assertEqual({}, output['volumes']['mojie_files'])
        self.assertTrue(all(service['restart'] == 'no' for service in output['services'].values()))
        spec['services']['backend']['environment']['MOJIE_SKILL_EXECUTOR_URL'] = 'http://skill-executor:8091'
        spec['services']['backend']['networks'] = {'default': {}, 'execution': {}}
        context = driver.make_record(spec, 'context', 18088, RELEASE['images'], 'context')
        current = read_json(context['compose'])
        self.assertNotIn('skill-executor', current['services'])
        self.assertNotIn('execution', current['networks'])
        self.assertNotIn('execution', current['services']['backend']['networks'])
        self.assertNotIn('MOJIE_SKILL_EXECUTOR_URL', current['services']['backend']['environment'])
        self.assertEqual(['backend', 'nginx'], driver.application_services(context))
        self.assertEqual(['backend', 'skill-executor', 'nginx'], driver.application_services(candidate))
        with patch.object(driver, 'command', return_value=MagicMock()) as command:
            with patch.object(driver, 'verify', return_value=None):
                driver.resume(context)
            command.assert_called_once_with(context, 'start', 'postgres', 'backend', 'nginx')


    def test_initial_registration_rejects_shared_or_remapped_data(self):
        spec = {'volumes': {name: {'name': 'live_' + name} for name in ('mojie_files', 'mojie_pgdata')},
                'services': {service: {'volumes': [{'target': target, 'type': 'volume', 'source': source}]}
                             for service, target, source in [('backend', '/data', 'mojie_files'),
                                  ('files-init', '/data', 'mojie_files'), ('postgres', '/var/lib/postgresql', 'mojie_pgdata')]}}
        validate_initial_storage(spec, 'live')
        for options in ({'external': True}, {'name': 'shared_files'}, {'driver_opts': {'device': '/shared'}}, {'driver': 'nfs'}):
            candidate = copy.deepcopy(spec)
            candidate['volumes']['mojie_files'].update(options)
            with self.assertRaises(RuntimeError): validate_initial_storage(candidate, 'live')
        candidate = copy.deepcopy(spec)
        candidate['services']['backend']['volumes'][0]['source'] = 'other_files'
        with self.assertRaises(RuntimeError): validate_initial_storage(candidate, 'live')

    def test_gateway_waits_for_reload_and_requires_the_selected_healthy_upstream(self):
        driver = DockerDriver({'root': str(self.root), 'gatewayPort': 8088})
        maintenance = urllib.error.HTTPError('http://localhost/', 503, 'maintenance', {},
                                            io.BytesIO('墨界正在维护'.encode()))
        transient = urllib.error.HTTPError('http://localhost/probe', 502, 'reload', {}, None)
        stale = MagicMock()
        stale.__enter__.return_value.headers = {'X-Mojie-Upstream': 'http://127.0.0.1:18089'}
        healthy = MagicMock()
        healthy.__enter__.return_value.headers = {'X-Mojie-Upstream': 'http://127.0.0.1:18088'}
        healthy.__enter__.return_value.read.return_value = b'{"data":{"status":"UP"}}'
        with patch('mojie_deploy.http_json', return_value={'data': {'active': True}}), \
             patch('mojie_deploy.urllib.request.urlopen', side_effect=[maintenance, transient, stale, healthy]) as calls, \
             patch('mojie_deploy.time.sleep'):
            driver.verify_gateway(self.current)
        self.assertEqual(4, calls.call_count)


class BackupFailureTest(unittest.TestCase):
    def test_context_only_backup_stops_and_recovers_only_existing_writers(self):
        spec = importlib.util.spec_from_file_location('backup', Path(__file__).with_name('backup-conversation-skills.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            def check_output(command, **kwargs):
                if '--services' in command: return 'backend\n'
                if 'inspect' in command: return json.dumps([{'Mounts': [{'Destination': '/data', 'Type': 'volume', 'Name': 'files'}]}])
                if 'psql' in command: return '{"versions":[],"files":[],"artifacts":[]}'
                return 'container'
            def run(command, **kwargs):
                if 'pg_dump' in command: raise subprocess.CalledProcessError(1, command)
            with patch.object(module.subprocess, 'check_output', side_effect=check_output), \
                 patch.object(module.subprocess, 'run', side_effect=run) as calls:
                with self.assertRaises(subprocess.CalledProcessError):
                    module.backup(['docker', 'compose'], Path(directory) / 'backup')
                commands = [call.args[0] for call in calls.call_args_list]
                self.assertIn(['docker', 'compose', 'stop', '-t', '60', 'backend'], commands)
                self.assertIn(['docker', 'compose', 'start', 'backend'], commands)
                self.assertFalse(any('skill-executor' in command for command in commands))

    def test_file_validation_detects_startup_damage_and_allows_only_expired_artifacts(self):
        import hashlib
        import sys
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            package = root / 'data/skills/packages/ready/SKILL.md'
            package.parent.mkdir(parents=True)
            package.write_bytes(b'preserved guidance')
            manifest = {'versions': [{'id': 'ready', 'status': 'READY'}, {'id': 'gone', 'status': 'UNAVAILABLE'}],
                        'files': [{'version_id': version, 'relative_path': 'SKILL.md', 'mime': 'text/markdown',
                                   'size_bytes': 18, 'hash': hashlib.sha256(package.read_bytes()).hexdigest()}
                                  for version in ('ready', 'gone')],
                        'artifacts': [{'storage_key': 'artifacts/expired', 'size_bytes': 1,
                                       'hash': 'a' * 64, 'expires_at': '2000-01-01T00:00:00+00:00'}]}
            write_json(root / 'manifest.json', manifest)
            command = [sys.executable, str(Path(__file__).with_name('verify-skill-files.py')),
                       '--manifest', str(root / 'manifest.json'), '--data-dir', str(root / 'data')]
            def check(*args): return subprocess.run([*command, *args], capture_output=True)
            self.assertNotEqual(0, check().returncode)
            self.assertEqual(0, check('--skip-expired-artifacts').returncode)
            manifest['artifacts'][0]['expires_at'] = '2999-01-01T00:00:00+00:00'
            write_json(root / 'manifest.json', manifest)
            self.assertNotEqual(0, check('--skip-expired-artifacts').returncode)
            manifest['artifacts'] = []
            write_json(root / 'manifest.json', manifest)
            package.write_bytes(b'corrupted guidance')
            result = check('--skip-expired-artifacts')
            self.assertNotEqual(0, result.returncode)
            self.assertIn(b'corrupt restored package file', result.stderr)

    def test_failed_backup_has_no_complete_marker_and_keep_stopped_does_not_resume(self):
        spec = importlib.util.spec_from_file_location('backup', Path(__file__).with_name('backup-conversation-skills.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'backup'
            def check_output(command, **kwargs):
                if 'inspect' in command: return json.dumps([{'Mounts': [{'Destination': '/data', 'Type': 'volume', 'Name': 'actual_files'}]}])
                if 'psql' in command: return '{"versions":[],"files":[],"artifacts":[]}'
                if '--services' in command: return 'backend\nskill-executor\n'
                return 'container'
            def run(command, **kwargs):
                if 'tar' in command: raise subprocess.CalledProcessError(1, command)
            with patch.object(module.subprocess, 'check_output', side_effect=check_output), patch.object(module.subprocess, 'run', side_effect=run) as calls:
                with self.assertRaises(subprocess.CalledProcessError): module.backup(['docker', 'compose'], output, keep_stopped=True)
                self.assertFalse((output / 'complete.json').exists())
                self.assertFalse(any('start' in call.args[0] for call in calls.call_args_list))
                self.assertTrue(any('actual_files:/data:ro' in call.args[0] for call in calls.call_args_list))


class ReleaseManifestTest(unittest.TestCase):
    def test_ci_assembles_a_complete_context_only_release(self):
        import sys
        spec = importlib.util.spec_from_file_location('release', Path(__file__).with_name('create-deployment-release.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, reference in RELEASE['images'].items():
                write_json(root / (name + '.json'), {name: reference})
            output = root / 'release.json'
            with patch.object(sys, 'argv', ['release', '--parts', directory, '--output', str(output)]), \
                 patch.dict('os.environ', {'GITHUB_REPOSITORY': RELEASE['repository'],
                     'GITHUB_SHA': RELEASE['commit'], 'GITHUB_REF': RELEASE['sourceRef'],
                     'GITHUB_RUN_ID': '42', 'GITHUB_RUN_NUMBER': '2', 'GITHUB_RUN_ATTEMPT': '1'}):
                module.main()
            result = validate_release(read_json(output), RELEASE['repository'])
            self.assertEqual(2, result['deploymentContract'])
            self.assertEqual({'backend', 'web'}, set(result['images']))


if __name__ == '__main__': unittest.main()
