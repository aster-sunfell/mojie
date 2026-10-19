#!/usr/bin/env python3
"""Exercise Docker rollback with disposable volumes and locally built fixture images.

The bad backend fixture must contain a failing V10 migration; the new fixture
must contain a successful V10. This harness does not exercise GitHub attestations.
"""
import argparse
import io
import json
import os
from pathlib import Path
import socket
import shutil
import tempfile
import urllib.request
import urllib.error
import uuid
import zipfile

from mojie_deploy import BASE, Deployment, DockerDriver, execute, read_json, write_json


def free_port():
    with socket.socket() as connection:
        connection.bind(('127.0.0.1', 0))
        return connection.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--old-backend', default='mojie-rollback-verify-backend:old')
    parser.add_argument('--new-backend', default='mojie-rollback-verify-backend:new')
    parser.add_argument('--bad-backend', default='mojie-rollback-verify-backend:bad')
    parser.add_argument('--web', default='mojie-rollback-verify-web:local')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    root = Path(tempfile.mkdtemp(prefix='mojie-rollback-check-'))
    (root / 'control').mkdir(mode=0o755)
    ports = [free_port(), free_port()]
    gateway_port = free_port()
    token = uuid.uuid4().hex[:10]
    records = []
    config = {'root': str(root), 'applicationPorts': ports, 'gatewayPort': gateway_port,
              'gatewayContainer': 'mojie-verify-gateway-' + token, 'drainTimeoutSeconds': 10}
    env = dict(os.environ, MOJIE_IMAGE_TAG='verify', MOJIE_DB_PASSWORD='isolated_test_only',
               MOJIE_CURSOR_SIGNING_KEY='isolated-test-cursor-key', MOJIE_MODEL_API_KEY='',
               ANTHROPIC_API_KEY='', MOJIE_CONTROL_DIR=str(root / 'control'),
               MOJIE_BACKEND_IMAGE=args.old_backend,
               MOJIE_WEB_IMAGE=args.web)
    resolved = json.loads(execute(['docker', 'compose', '-f', str(BASE / 'deploy/compose.prod.yaml'), 'config', '--format', 'json'], env=env).stdout)
    resolved['services']['backend']['environment']['MOJIE_MODEL_BASE_URL'] = ''
    resolved['services']['backend']['environment']['MOJIE_MODEL_ID'] = ''
    for name in ('MOJIE_MODEL_API_KEY', 'ANTHROPIC_API_KEY'):
        resolved['services']['backend']['environment'][name] = ''

    class LocalDriver(DockerDriver):
        fixture = args.bad_backend
        fail_gateway = False
        def prepare(self, release, current):
            port = next(p for p in ports if p != current['port'])
            identity = 'verify-' + token + '-' + uuid.uuid4().hex[:6]
            record = self.make_record(resolved, 'mojie-' + identity, port,
                {'backend': self.fixture, 'web': args.web}, identity)
            records.append(record)
            return record
        def start(self, record):
            if read_json(record['compose'])['services']['backend']['image'] == args.bad_backend:
                try:
                    self.command(record, 'up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '35')
                except RuntimeError:
                    logs = self.command(record, 'logs', '--no-color', 'backend').stdout.decode()
                    if 'intentionally_missing_column' not in logs:
                        raise RuntimeError('Candidate failed for a reason other than the fixture migration')
                    raise RuntimeError('Expected fixture migration failure')
            else:
                super().start(record)
        def verify_gateway(self, record):
            super().verify_gateway(record)
            if self.fail_gateway:
                self.fail_gateway = False
                raise RuntimeError('Injected failure after a real gateway switch')

    driver = LocalDriver(config)
    initial = driver.make_record(resolved, 'mojie-verify-old-' + token, ports[0],
              {'backend': args.old_backend, 'web': args.web}, 'initial')
    records.append(initial)
    report = {'root': str(root), 'scope': 'Disposable Docker environments; local fixture images, no GitHub attestation verification', 'checks': []}
    gateway_started = False
    try:
        driver.gate(True)
        driver.start(initial)
        driver.verify(initial)
        gateway_config = (BASE / 'deploy/gateway/default.conf.template').read_text().replace('${MOJIE_GATEWAY_BIND}', '127.0.0.1').replace('${MOJIE_GATEWAY_PORT}', str(gateway_port))
        (root / 'gateway.conf').write_text(gateway_config)
        shutil.copy(BASE / 'web/public/maintenance.html', root / 'maintenance.html')
        (root / 'control/upstream.conf').write_text(f'set $mojie_upstream "http://127.0.0.1:{ports[0]}";\n')
        execute(['docker', 'run', '-d', '--name', config['gatewayContainer'], '--network', 'host',
                 '-v', str(root / 'control') + ':/control:ro,z', '-v', str(root / 'gateway.conf') + ':/etc/nginx/conf.d/default.conf:ro,z',
                 '-v', str(root / 'maintenance.html') + ':/usr/share/nginx/html/maintenance.html:ro,z', 'nginx:1.28-alpine'])
        gateway_started = True
        driver.switch(initial)
        driver.verify_gateway(initial)
        report['checks'].append('Independent gateway serves maintenance while API is blocked')
        write_json(root / 'state.json', {'phase': 'COMMITTED', 'current': initial, 'currentRunNumber': 0, 'failedReleases': []})
        deployment = Deployment(root, driver)
        driver.gate(False)
        def api(path, method='GET', body=None):
            data = None if body is None else json.dumps(body).encode()
            request = urllib.request.Request(f'http://127.0.0.1:{gateway_port}/api/v1' + path,
                       data=data, method=method, headers={'Content-Type': 'application/json'})
            with urllib.request.urlopen(request, timeout=20) as response:
                if response.status == 204: return None
                return json.load(response)['data']
        project = api('/projects', 'POST', {'title': '隔离回退验收'})
        chapter = api('/projects/' + project['id'] + '/chapters', 'POST', {'title': '必须保留的章节'})
        chapter = api('/chapters/' + chapter['id'] + '/content', 'PUT', {'content': '迁移前已提交的正文。', 'expectedVersion': chapter['version']})
        package = io.BytesIO()
        with zipfile.ZipFile(package, 'w') as archive:
            archive.writestr('SKILL.md', '---\nname: rollback-check\ndescription: 隔离迁移验收指导\n---\n保留此指导。\n')
            archive.writestr('references/evidence.txt', '需要随数据库一起保留的文件。')
        boundary = 'mojie-' + token
        data = b'--' + boundary.encode() + b'\r\nContent-Disposition: form-data; name="files"; filename="check.zip"\r\nContent-Type: application/zip\r\n\r\n' + package.getvalue() + b'\r\n--' + boundary.encode() + b'--\r\n'
        request = urllib.request.Request(f'http://127.0.0.1:{gateway_port}/api/v1/skills/import', data=data,
                    headers={'Content-Type': 'multipart/form-data; boundary=' + boundary})
        with urllib.request.urlopen(request, timeout=30) as response:
            skill = json.load(response)['data']
        assert skill['status'] == 'READY'
        original = driver.fingerprint(initial)
        def release(number): return {'runId': number, 'runAttempt': 1, 'runNumber': number}
        try: deployment.deploy(release(1))
        except RuntimeError as error:
            report['migrationFailure'] = str(error)
            assert 'Expected fixture migration failure' in str(error), str(error)
        else: raise AssertionError('Failing migration was accepted')
        assert read_json(root / 'state.json')['phase'] == 'ROLLED_BACK'
        assert driver.fingerprint(initial) == original
        assert driver.sql(initial, "SELECT count(*) FROM information_schema.tables WHERE table_name='deploy_probe'") == '0'
        assert api('/chapters/' + chapter['id'])['content'] == '迁移前已提交的正文。'
        report['checks'].append('Actual failing V10 migration rolls back to untouched V9 environment and chapter content')
        driver.fixture = args.new_backend
        driver.fail_gateway = True
        try: deployment.deploy(release(2))
        except RuntimeError as error:
            report['switchFailure'] = str(error)
            print(str(error), flush=True)
            assert 'Injected failure after a real gateway switch' in str(error), str(error)
        else: raise AssertionError('Gateway failure was accepted')
        assert read_json(root / 'state.json')['phase'] == 'ROLLED_BACK'
        assert driver.fingerprint(initial) == original
        report['checks'].append('Post-switch failure restores the old upstream and data')
        deployment.deploy(release(3))
        state = read_json(root / 'state.json')
        assert state['phase'] == 'COMMITTED'
        current = state['current']
        assert driver.sql(current, "SELECT max(version::int) FROM flyway_schema_history") == '10'
        assert api('/chapters/' + chapter['id'])['content'] == '迁移前已提交的正文。'
        detail = api('/skills/' + skill['skillId'])
        assert detail['skill']['id'] == skill['skillId']
        report['checks'].append('Successful V10 commit preserves chapter data and restored Skill package')
        api('/chapters/' + chapter['id'] + '/content', 'PUT', {'content': '新版本开放后保存的正文。', 'expectedVersion': chapter['version']})
        driver.stop(current)
        deployment.recover()
        assert api('/chapters/' + chapter['id'])['content'] == '新版本开放后保存的正文。'
        report['checks'].append('Committed recovery retains new writes and restarts the current environment')
        report['status'] = 'PASS'
    finally:
        if report.get('status') != 'PASS':
            for record in records:
                try:
                    logs = driver.command(record, 'logs', '--no-color', '--tail', '35').stdout.decode()
                    (root / (record['project'] + '.log')).write_text(logs)
                except Exception:
                    pass
        if gateway_started:
            execute(['docker', 'rm', '-f', config['gatewayContainer']])
        for record in reversed(records):
            driver.command(record, 'down', '-v', '--remove-orphans')
        write_json(args.output, report)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__': main()
