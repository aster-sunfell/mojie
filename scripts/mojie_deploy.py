#!/usr/bin/env python3
"""Host-owned, digest-pinned deployment with isolated data and pre-commit rollback."""
import argparse
import contextlib
import copy
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import urllib.error
import uuid

BASE = Path(__file__).resolve().parent.parent
SERVICES = {'backend': 'backend', 'web': 'nginx'}
LEGACY_SERVICES = {**SERVICES, 'executor': 'skill-executor'}
DIGEST = r'sha256:[0-9a-f]{64}'
PRECOMMIT = {'PREPARED', 'DRAINING', 'FROZEN', 'CANDIDATE_READY', 'SWITCHED', 'ROLLING_BACK'}


def atomic_write(path, value, mode=0o600):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.tmp')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
    os.fchmod(fd, mode)
    with os.fdopen(fd, 'w') as output:
        output.write(value)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)
    fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def write_json(path, value, mode=0o600):
    atomic_write(path, json.dumps(value, ensure_ascii=False, indent=2) + '\n', mode)


def read_json(path):
    return json.loads(Path(path).read_text())


def validate_release(value, repository):
    prefix = 'ghcr.io/' + repository.lower()
    if value.get('schemaVersion') != 1 or value.get('repository') != repository:
        raise ValueError('Release schema or repository mismatch')
    if not re.fullmatch(r'[0-9a-f]{40}', value.get('commit', '')):
        raise ValueError('Invalid release commit')
    for name in ('runId', 'runNumber', 'runAttempt'):
        if type(value.get(name)) is not int or value[name] <= 0:
            raise ValueError('Invalid release sequence')
    contract = value.get('deploymentContract')
    if value.get('sourceRef') != 'refs/heads/main' or contract not in (1, 2):
        raise ValueError('Unsupported deployment contract or source branch')
    images = value.get('images', {})
    expected = LEGACY_SERVICES if contract == 1 else SERVICES
    if set(images) != set(expected):
        raise ValueError('A release must contain exactly the images required by its deployment contract')
    for name, ref in images.items():
        if not isinstance(ref, str) or not re.fullmatch(re.escape(prefix + '-' + name + '@') + DIGEST, ref):
            raise ValueError('Unapproved image repository or mutable image reference')
    return value


def release_id(release):
    return f"{release['runId']}-{release['runAttempt']}"


def validate_initial_storage(resolved, project):
    volumes = resolved.get('volumes', {})
    if set(volumes) != {'mojie_pgdata', 'mojie_files'}:
        raise RuntimeError('Initial environment must use the two default data volumes')
    for name, volume in volumes.items():
        if (volume.get('external') or volume.get('driver_opts') or
                volume.get('driver', 'local') != 'local' or
                volume.get('name', project + '_' + name) != project + '_' + name):
            raise RuntimeError('Initial environment must use independent default local volumes')
    for service, destination, volume in (('postgres', '/var/lib/postgresql', 'mojie_pgdata'),
                                         ('backend', '/data', 'mojie_files'),
                                         ('files-init', '/data', 'mojie_files')):
        mounts = [v for v in resolved['services'][service].get('volumes', []) if v['target'] == destination]
        if len(mounts) != 1 or mounts[0].get('type') != 'volume' or mounts[0].get('source') != volume:
            raise RuntimeError('Initial environment has an unsupported data mount')


def execute(args, **options):
    # Never use a shell and never print resolved Compose secrets.
    try:
        return subprocess.run([str(a) for a in args], check=True, timeout=options.pop('timeout', 900),
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, **options)
    except subprocess.CalledProcessError as error:
        raise RuntimeError(f'Command failed ({Path(str(args[0])).name}, exit {error.returncode}); inspect service logs on the host') from None


def http_json(port, path):
    with urllib.request.urlopen(f'http://127.0.0.1:{port}{path}', timeout=10) as response:
        return json.load(response)


class DockerDriver:
    def __init__(self, config):
        self.config = config
        self.root = Path(config['root'])
        self.control = self.root / 'control'
        self.gateway = config.get('gatewayContainer', 'mojie-gateway')

    def compose(self, record):
        return ['docker', 'compose', '-f', record['compose'], '-p', record['project']]

    def command(self, record, *args, **kwargs):
        return execute([*self.compose(record), *args], **kwargs)

    def container(self, record, service):
        value = self.command(record, 'ps', '-a', '-q', service).stdout.decode().strip()
        if not value or '\n' in value:
            raise RuntimeError('Missing or ambiguous service container: ' + service)
        return value

    def sql(self, record, query):
        container = self.container(record, 'postgres')
        return execute(['docker', 'exec', container, 'psql', '-U', 'mojie', '-d', 'mojie', '-At',
                        '-v', 'ON_ERROR_STOP=1', '-c', query]).stdout.decode().strip()

    def gate(self, active):
        if active:
            atomic_write(self.control / 'maintenance', 'maintenance\n', 0o644)
        else:
            (self.control / 'maintenance').unlink(missing_ok=True)
            fd = os.open(self.control, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
        write_json(self.control / 'public-status.json', {'data': {'active': active, 'retryAfterSeconds': 5}}, 0o644)

    def switch(self, record):
        atomic_write(self.control / 'upstream.conf', f'set $mojie_upstream "http://127.0.0.1:{record["port"]}";\n', 0o644)
        execute(['docker', 'exec', self.gateway, 'nginx', '-t'])
        execute(['docker', 'exec', self.gateway, 'nginx', '-s', 'reload'])

    def prepare(self, release, current):
        for image in release['images'].values():
            execute(['docker', 'pull', image])
        # Installed Compose is trusted local configuration, never taken from an artifact.
        env = dict(os.environ, MOJIE_CONTROL_DIR=str(self.control))
        resolved = json.loads(execute(['docker', 'compose', '--env-file', self.config['envFile'],
                                      '-f', self.config['composeFile'], 'config', '--format', 'json'], env=env).stdout)
        for service in ('postgres', 'files-init'):
            execute(['docker', 'pull', resolved['services'][service]['image']])
        execute(['docker', 'pull', 'alpine:3.22'])
        volumes = self.volumes(current)
        sizes = execute(['docker', 'run', '--rm', '--network', 'none', '-v', volumes['postgres'] + ':/pg:ro',
                         '-v', volumes['files'] + ':/files:ro', 'alpine:3.22', 'du', '-sk', '/pg', '/files']).stdout.decode()
        required = sum(int(line.split()[0]) for line in sizes.splitlines()) * 1024 * 4
        minimum = self.config.get('minFreeBytes', 1024 ** 3)
        if shutil.disk_usage(self.root).free < required + minimum:
            raise RuntimeError('Insufficient disk space for backup and restored candidate')
        docker_root = execute(['docker', 'info', '--format', '{{.DockerRootDir}}']).stdout.decode().strip()
        if shutil.disk_usage(docker_root).free < required + minimum:
            raise RuntimeError('Insufficient Docker storage for restored candidate')
        token = release_id(release) + '-' + uuid.uuid4().hex[:8]
        port = next(p for p in self.config.get('applicationPorts', [18088, 18089]) if p != current['port'])
        return self.make_record(resolved, 'mojie-r' + token, port, release['images'], token)

    def make_record(self, resolved, project, port, images, token):
        resolved = copy.deepcopy(resolved)
        directory = self.root / 'releases' / token
        directory.mkdir(parents=True, exist_ok=False, mode=0o700)
        resolved.pop('name', None)
        # Always use per-project data volumes. External/shared data is disallowed.
        resolved['volumes'] = {'mojie_pgdata': {}, 'mojie_files': {}}
        for network in resolved.get('networks', {}).values():
            if network.get('external'):
                raise RuntimeError('Managed deployment disallows shared external networks')
            network.pop('name', None)
        for service in resolved['services'].values():
            service['restart'] = 'no'
        if 'executor' not in images:
            resolved['services'].pop('skill-executor', None)
            resolved.get('networks', {}).pop('execution', None)
            backend = resolved['services']['backend']
            backend.get('environment', {}).pop('MOJIE_SKILL_EXECUTOR_URL', None)
            networks = backend.get('networks', {})
            if isinstance(networks, dict): networks.pop('execution', None)
            elif 'execution' in networks: networks.remove('execution')
        for name, image in images.items():
            service = LEGACY_SERVICES[name]
            if service not in resolved['services']:
                raise RuntimeError('Installed Compose does not support this release: ' + service)
            resolved['services'][service]['image'] = image
        backend = resolved['services']['backend']
        backend['environment']['MOJIE_MAINTENANCE_FILE'] = '/run/mojie-control/maintenance'
        backend['volumes'] = [v for v in backend['volumes'] if v['target'] != '/run/mojie-control']
        backend['volumes'].append({'type': 'bind', 'source': str(self.control), 'target': '/run/mojie-control', 'read_only': True, 'bind': {'selinux': 'z', 'create_host_path': True}})
        # Retained contract-1 environments can still be restored during rollback.
        if 'skill-executor' in resolved['services']:
            executor = resolved['services']['skill-executor']
            work_dir = str(self.root / 'executor' / token)
            executor['environment']['MOJIE_EXECUTOR_WORK_DIR'] = work_dir
            executor['volumes'] = [v for v in executor['volumes'] if v['target'] == '/var/run/docker.sock']
            executor['volumes'].append({'type': 'bind', 'source': work_dir, 'target': work_dir, 'bind': {'selinux': 'z', 'create_host_path': True}})
        resolved['services']['nginx']['ports'] = [{'target': 80, 'published': str(port), 'host_ip': '127.0.0.1', 'protocol': 'tcp'}]
        path = directory / 'compose.json'
        write_json(path, resolved)
        return {'project': project, 'port': port, 'compose': str(path), 'directory': str(directory)}

    def volumes(self, record):
        result = {}
        for service, key, destination in (('postgres', 'postgres', '/var/lib/postgresql'), ('backend', 'files', '/data')):
            data = json.loads(execute(['docker', 'inspect', self.container(record, service)]).stdout)[0]
            result[key] = next(m['Name'] for m in data['Mounts'] if m['Destination'] == destination and m['Type'] == 'volume')
        return result

    def drain(self, current):
        deadline = time.monotonic() + self.config.get('drainTimeoutSeconds', 360)
        while True:
            status = http_json(current['port'], '/api/v1/system/maintenance')['data']
            if status.get('active') is not True or type(status.get('activeWrites')) is not int:
                raise RuntimeError('Old backend does not acknowledge the shared maintenance marker')
            active = int(self.sql(current, "SELECT (SELECT count(*) FROM tb_agent_run WHERE status IN ('QUEUED','RUNNING')) + (SELECT count(*) FROM tb_skill_upload WHERE status IN ('UPLOADING','VALIDATING')) + (SELECT count(*) FROM tb_skill_execution WHERE status='STARTED')"))
            if active == 0 and status['activeWrites'] == 0:
                return
            if time.monotonic() >= deadline:
                raise RuntimeError('Drain timed out; existing work is not interrupted')
            time.sleep(2)

    def application_services(self, record):
        installed = read_json(record['compose'])['services']
        return [name for name in ('backend', 'skill-executor', 'nginx') if name in installed]

    def freeze(self, current):
        services = self.application_services(current)
        self.command(current, 'stop', '-t', '60', *services)
        for service in services:
            info = json.loads(execute(['docker', 'inspect', self.container(current, service)]).stdout)[0]
            if info['State']['Running']:
                raise RuntimeError('A writer did not stop')

    def fingerprint(self, record):
        # Stable user content columns survive the existing owner-removal migration.
        selections = {
            'projects': 'SELECT id,name FROM tb_project ORDER BY id',
            'chapters': 'SELECT id,project_id,title,content,version FROM tb_chapter ORDER BY id',
            'revisions': 'SELECT id,chapter_id,version,content FROM tb_chapter_revision ORDER BY id',
            'settings': 'SELECT id,project_id,title,content FROM tb_setting ORDER BY id',
            'conversations': 'SELECT id,project_id,title,last_seq FROM tb_conversation ORDER BY id',
            'messages': 'SELECT id,conversation_id,seq,role,content FROM tb_conversation_message ORDER BY id',
            'snapshots': 'SELECT id,run_id,hash FROM tb_agent_input_snapshot ORDER BY id',
        }
        return {name: self.sql(record, f"SELECT count(*) || ':' || encode(sha256(convert_to(COALESCE(string_agg(row_to_json(t)::text, E'\\n' ORDER BY t.id), ''), 'UTF8')), 'hex') FROM ({query}) t") for name, query in selections.items()}

    def backup(self, current):
        module_spec = importlib.util.spec_from_file_location('mojie_backup', BASE / 'scripts/backup-conversation-skills.py')
        module = importlib.util.module_from_spec(module_spec)
        module_spec.loader.exec_module(module)
        output = Path(current['directory']) / ('backup-' + uuid.uuid4().hex[:8])
        module.backup(self.compose(current), output, keep_stopped=True)
        return str(output)

    def restore(self, candidate, backup):
        backup = Path(backup)
        completed = read_json(backup / 'complete.json')
        for name in ('manifest.json', 'postgres.dump', 'files.tar'):
            path = backup / name
            digest = hashlib.sha256()
            with path.open('rb') as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b''):
                    digest.update(chunk)
            if completed['files'][name] != {'size': path.stat().st_size, 'sha256': digest.hexdigest()}:
                raise RuntimeError('Incomplete or corrupt backup')
        with tarfile.open(backup / 'files.tar') as archive:
            for entry in archive:
                path = Path(entry.name)
                if path.is_absolute() or '..' in path.parts or not (entry.isfile() or entry.isdir()):
                    raise RuntimeError('Unsafe backup archive')
        self.command(candidate, 'up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '180', 'postgres')
        # A completed one-shot job is not a continuously running --wait target.
        self.command(candidate, 'run', '--rm', '--no-deps', 'files-init')
        self.command(candidate, 'create', '--no-build', '--pull', 'never', 'backend')
        postgres = self.container(candidate, 'postgres')
        if self.sql(candidate, "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'") != '0':
            raise RuntimeError('Restore target must be an empty database')
        with (backup / 'postgres.dump').open('rb') as source:
            execute(['docker', 'exec', '-i', postgres, 'pg_restore', '-U', 'mojie', '-d', 'mojie', '--exit-on-error', '--single-transaction'], stdin=source)
        volume = self.volumes(candidate)['files']
        with (backup / 'files.tar').open('rb') as source:
            execute(['docker', 'run', '--rm', '-i', '--network', 'none', '-v', volume + ':/data', 'alpine:3.22', 'tar', '-C', '/data', '-xf', '-'], stdin=source)
        self.verify_files(candidate, backup)

    def verify_files(self, candidate, backup, after_start=False):
        backup = Path(backup)
        options = ['--skip-expired-artifacts'] if after_start else []
        # The host already runs Python. Verify a temporary copy without requiring a
        # Python runtime image, Docker socket in the application, or running uploaded code.
        with tempfile.TemporaryDirectory(prefix='verify-files-', dir=self.root) as temporary:
            data = Path(temporary) / 'data'
            data.mkdir()
            execute(['docker', 'cp', self.container(candidate, 'backend') + ':/data/.', str(data)])
            execute([sys.executable, str(BASE / 'scripts/verify-skill-files.py'),
                     '--manifest', str(backup / 'manifest.json'), '--data-dir', str(data), *options])

    def start(self, record):
        self.command(record, 'up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '180')

    def resume(self, record):
        # Start existing old containers; do not recreate them on a drain timeout.
        self.command(record, 'start', 'postgres', *self.application_services(record))
        deadline = time.monotonic() + 180
        while True:
            try:
                self.verify(record)
                return
            except Exception:
                if time.monotonic() >= deadline:
                    raise RuntimeError('Old environment did not recover') from None
                time.sleep(2)

    def stop(self, record):
        # Containers and volumes are retained for recovery and inspection.
        self.command(record, 'stop', '-t', '60')
        running = self.command(record, 'ps', '--status', 'running', '-q').stdout.strip()
        if running:
            raise RuntimeError('Cannot safely start another environment while this one is running')

    def verify(self, record, expected=None):
        if http_json(record['port'], '/api/v1/health').get('data', {}).get('status') != 'UP':
            raise RuntimeError('Backend is not UP')
        if http_json(record['port'], '/api/v1/system/maintenance').get('data', {}).get('active') is not True:
            raise RuntimeError('Candidate does not acknowledge maintenance')
        with urllib.request.urlopen(f'http://127.0.0.1:{record["port"]}/', timeout=10) as response:
            if response.status != 200 or b'<html' not in response.read().lower():
                raise RuntimeError('Web frontend is not available')
        if 'skill-executor' in self.application_services(record):
            container = self.container(record, 'skill-executor')
            execute(['docker', 'exec', container, 'python3', '-c', "import json,urllib.request; assert json.load(urllib.request.urlopen('http://127.0.0.1:8091/health',timeout=3))['status']=='UP'"])
        failed = self.sql(record, 'SELECT count(*) FROM flyway_schema_history WHERE NOT success')
        if failed != '0':
            raise RuntimeError('Flyway contains failed migrations')
        if expected is not None and self.fingerprint(record) != expected:
            raise RuntimeError('User content changed during migration or startup')
        http_json(record['port'], '/api/v1/projects?limit=1')
        http_json(record['port'], '/api/v1/system/capabilities')

    def verify_gateway(self, record):
        port = self.config.get('gatewayPort', 8088)
        if http_json(port, '/api/v1/system/maintenance').get('data', {}).get('active') is not True:
            raise RuntimeError('Gateway status does not match maintenance')
        maintenance_page = False
        try:
            urllib.request.urlopen(f'http://127.0.0.1:{port}/', timeout=10)
        except urllib.error.HTTPError as response:
            if response.code == 503 and '墨界正在维护' in response.read().decode():
                maintenance_page = True
        if not maintenance_page:
            raise RuntimeError('Gateway did not serve its independent maintenance page')
        deadline = time.monotonic() + 15
        while True:
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{port}/__mojie_deploy_probe', timeout=3) as response:
                    if response.headers.get('X-Mojie-Upstream') == f'http://127.0.0.1:{record["port"]}' and json.load(response).get('data', {}).get('status') == 'UP':
                        return
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
                # A reload is asynchronous; an old worker can briefly route to
                # the stopped upstream. Keep maintenance on until routing settles.
                pass
            if time.monotonic() >= deadline:
                raise RuntimeError('Gateway did not route to the selected healthy environment')
            time.sleep(0.2)


class Deployment:
    def __init__(self, root, driver):
        self.root = Path(root)
        self.driver = driver
        self.state_path = self.root / 'state.json'

    def save(self, state):
        write_json(self.state_path, state)
        print(json.dumps({'phase': state['phase'], 'project': state.get('current', {}).get('project')}, ensure_ascii=False), flush=True)

    def deploy(self, release, retry=False):
        state = read_json(self.state_path)
        if state['phase'] not in {'COMMITTED', 'ROLLED_BACK'}:
            raise RuntimeError('Interrupted deployment requires recover before another update')
        number = release['runNumber']
        if number <= state.get('currentRunNumber', 0):
            raise RuntimeError('Refusing an automatic downgrade or replay')
        identity = release_id(release)
        if identity in state.get('failedReleases', []) and not retry:
            raise RuntimeError('Release previously failed; explicit retry is required')
        current = state['current']
        candidate = self.driver.prepare(release, current)
        state.update(phase='PREPARED', previous=current, candidate=candidate, pendingRelease=release)
        self.save(state)
        try:
            self.driver.gate(True)
            state['phase'] = 'DRAINING'
            self.save(state)
            self.driver.drain(current)
            self.driver.freeze(current)
            state['phase'] = 'FROZEN'
            self.save(state)
            expected = self.driver.fingerprint(current)
            state['backup'] = self.driver.backup(current)
            state['expectedContent'] = expected
            self.save(state)
            self.driver.stop(current)
            self.driver.restore(candidate, state['backup'])
            self.driver.start(candidate)
            self.driver.verify(candidate, expected)
            self.driver.verify_files(candidate, state['backup'], after_start=True)
            state['phase'] = 'CANDIDATE_READY'
            self.save(state)
            self.driver.switch(candidate)
            state['phase'] = 'SWITCHED'
            self.save(state)
            self.driver.verify_gateway(candidate)
            # Once this record is durable, recovery MUST preserve candidate data.
            state.update(phase='COMMITTED', current=candidate, currentRunNumber=number,
                         currentRelease=release, committedAt=time.time())
            self.save(state)
            self.driver.gate(False)
        except Exception:
            if state['phase'] == 'COMMITTED':
                # It is unsafe to assume no user writes have reached this version.
                self.driver.gate(True)
                raise RuntimeError('Release committed; recover current environment without reverting its data') from None
            self.rollback(state)
            raise
        return state

    def rollback(self, state):
        self.driver.gate(True)
        state['phase'] = 'ROLLING_BACK'
        self.save(state)
        # If stopping candidate fails, leave maintenance enabled and do not start old writers.
        self.driver.stop(state['candidate'])
        previous = state['previous']
        self.driver.resume(previous)
        self.driver.verify(previous)
        self.driver.switch(previous)
        self.driver.verify_gateway(previous)
        failed = state.setdefault('failedReleases', [])
        identity = release_id(state['pendingRelease'])
        if identity not in failed:
            failed.append(identity)
        state.update(phase='ROLLED_BACK', current=previous)
        self.save(state)
        self.driver.gate(False)

    def recover(self):
        state = read_json(self.state_path)
        self.driver.gate(True)
        if state['phase'] in PRECOMMIT:
            self.rollback(state)
        elif state['phase'] in {'COMMITTED', 'ROLLED_BACK'}:
            # Stop retained environments even if an operator started one manually.
            for record in (state.get('previous'), state.get('candidate')):
                if record and record['project'] != state['current']['project']:
                    self.driver.stop(record)
            self.driver.start(state['current'])
            self.driver.verify(state['current'])
            self.driver.switch(state['current'])
            self.driver.verify_gateway(state['current'])
            self.driver.gate(False)
        else:
            raise RuntimeError('Unknown deployment phase; maintenance remains enabled')


@contextlib.contextmanager
def deployment_lock(root):
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (root / 'deployment.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def download_verified_release(config, run_id, destination):
    repository = config['repository']
    run = json.loads(execute(['gh', 'api', f'repos/{repository}/actions/runs/{run_id}']).stdout)
    if run['conclusion'] != 'success' or run['head_branch'] != 'main' or run['event'] not in {'push', 'workflow_dispatch'} or run['path'] != '.github/workflows/images.yml':
        raise RuntimeError('Only successful main image workflows may be deployed')
    execute(['gh', 'run', 'download', str(run_id), '--repo', repository,
             '--name', 'deployment-release', '--dir', str(destination)])
    manifest = Path(destination) / 'release.json'
    execute(['gh', 'attestation', 'verify', str(manifest), '--repo', repository,
             '--signer-workflow', repository + '/.github/workflows/images.yml', '--source-ref', 'refs/heads/main',
             '--source-digest', run['head_sha'], '--deny-self-hosted-runners'])
    release = validate_release(read_json(manifest), repository)
    if release['runId'] != int(run_id) or release['runNumber'] != run['run_number'] or release['runAttempt'] != run['run_attempt'] or release['commit'] != run['head_sha']:
        raise RuntimeError('Attested release does not match the selected workflow run')
    return release


def register(config, driver, project, port):
    root = Path(config['root'])
    if (root / 'state.json').exists():
        raise RuntimeError('An active deployment is already registered')
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,62}', project) or port not in config.get('applicationPorts', [18088, 18089]):
        raise ValueError('Invalid initial project or application port')
    driver.control.mkdir(parents=True, exist_ok=True, mode=0o755)
    env = dict(os.environ, MOJIE_CONTROL_DIR=str(driver.control), MOJIE_HTTP_PORT=str(port), MOJIE_HTTP_BIND='127.0.0.1')
    resolved = json.loads(execute(['docker', 'compose', '--env-file', config['envFile'], '-f', config['composeFile'], '-p', project, 'config', '--format', 'json'], env=env).stdout)
    validate_initial_storage(resolved, project)
    images = {}
    prefix = 'ghcr.io/' + config['repository'].lower()
    containers = []
    installed_services = LEGACY_SERVICES if 'skill-executor' in resolved['services'] else SERVICES
    for name, service in installed_services.items():
        command = ['docker', 'compose', '--env-file', config['envFile'], '-f', config['composeFile'], '-p', project, 'ps', '-a', '-q', service]
        container = execute(command, env=env).stdout.decode().strip()
        info = json.loads(execute(['docker', 'inspect', container]).stdout)[0]
        containers.append(container)
        image = json.loads(execute(['docker', 'image', 'inspect', info['Image']]).stdout)[0]
        images[name] = next(ref for ref in image.get('RepoDigests', []) if re.fullmatch(re.escape(prefix + '-' + name + '@') + DIGEST, ref))
        if service == 'backend':
            if not any(m['Destination'] == '/data' and m['Type'] == 'volume' and
                       m.get('Name') == project + '_mojie_files' for m in info['Mounts']):
                raise RuntimeError('Running backend does not use the expected independent file volume')
            source = next(m['Source'] for m in info['Mounts'] if m['Destination'] == '/run/mojie-control')
            if Path(source).resolve() != driver.control.resolve():
                raise RuntimeError('Backend must mount the managed control directory before registration')
    status = http_json(port, '/api/v1/system/maintenance')['data']
    if type(status.get('activeWrites')) is not int:
        raise RuntimeError('First deploy an application version with maintenance support')
    postgres = execute(['docker', 'compose', '--env-file', config['envFile'], '-f', config['composeFile'], '-p', project, 'ps', '-q', 'postgres'], env=env).stdout.decode().strip()
    mounts = json.loads(execute(['docker', 'inspect', postgres]).stdout)[0]['Mounts']
    if not any(m['Destination'] == '/var/lib/postgresql' and m['Type'] == 'volume' and
               m.get('Name') == project + '_mojie_pgdata' for m in mounts):
        raise RuntimeError('Running database does not use the expected independent data volume')
    containers.append(postgres)
    record = driver.make_record(resolved, project, port, images, 'initial-' + uuid.uuid4().hex[:8])
    if 'skill-executor' in resolved['services']:
        # Registration preserves an existing executor's mounts until that release retires.
        spec = read_json(record['compose'])
        spec['services']['skill-executor'] = copy.deepcopy(resolved['services']['skill-executor'])
        spec['services']['skill-executor']['image'] = images['executor']
        spec['services']['skill-executor']['restart'] = 'no'
        write_json(record['compose'], spec)
    for container in containers:
        execute(['docker', 'update', '--restart', 'no', container])
    driver.gate(True)
    atomic_write(driver.control / 'upstream.conf', f'set $mojie_upstream "http://127.0.0.1:{port}";\n', 0o644)
    write_json(root / 'state.json', {'phase': 'COMMITTED', 'current': record, 'currentRunNumber': 0, 'failedReleases': []})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', default='/etc/mojie/deploy.json')
    commands = parser.add_subparsers(dest='command', required=True)
    initial = commands.add_parser('register')
    initial.add_argument('--project', default='mojie-prod')
    initial.add_argument('--port', type=int, default=18088)
    deploy = commands.add_parser('deploy')
    deploy.add_argument('--run-id', type=int, required=True)
    deploy.add_argument('--retry', action='store_true')
    commands.add_parser('poll')
    commands.add_parser('recover')
    commands.add_parser('status')
    args = parser.parse_args()
    config = read_json(args.config)
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', config['repository']):
        raise ValueError('Invalid configured repository')
    for key in ('root', 'composeFile', 'envFile'):
        if not Path(config[key]).is_absolute():
            raise ValueError('Host paths must be absolute')
    driver = DockerDriver(config)
    with deployment_lock(config['root']):
        deployment = Deployment(config['root'], driver)
        if args.command == 'register':
            register(config, driver, args.project, args.port)
        elif args.command == 'status':
            state = read_json(deployment.state_path)
            print(json.dumps({key: state.get(key) for key in ('phase', 'currentRunNumber', 'failedReleases')}, indent=2))
        elif args.command == 'recover':
            deployment.recover()
        else:
            state = read_json(deployment.state_path)
            if state['phase'] not in {'COMMITTED', 'ROLLED_BACK'}:
                deployment.recover()
                state = read_json(deployment.state_path)
            run_id = getattr(args, 'run_id', None)
            if args.command == 'poll':
                runs = json.loads(execute(['gh', 'api', f'repos/{config["repository"]}/actions/workflows/images.yml/runs?branch=main&status=success&per_page=20']).stdout)['workflow_runs']
                runs = [run for run in runs if run['event'] in {'push', 'workflow_dispatch'} and run['run_number'] > state.get('currentRunNumber', 0)]
                if not runs:
                    return
                run = max(runs, key=lambda value: value['run_number'])
                if f"{run['id']}-{run['run_attempt']}" in state.get('failedReleases', []):
                    return
                run_id = run['id']
            with tempfile.TemporaryDirectory(prefix='mojie-release-') as temporary:
                release = download_verified_release(config, run_id, temporary)
                deployment.deploy(release, getattr(args, 'retry', False))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit(str(error)) from None
