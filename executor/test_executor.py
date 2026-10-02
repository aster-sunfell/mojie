"""Real Docker acceptance tests; use downloaded digest-pinned Python/Node images."""
import base64
import os
import pathlib
import tempfile
import threading
import time
import unittest
import uuid
os.environ['MOJIE_EXECUTOR_WORK_DIR'] = tempfile.mkdtemp(prefix='mojie-executor-test-')
import server
server.IMAGES['python'] = 'python@sha256:a3ab0b966bc4e91546a033e22093cb840908979487a9fc0e6e38295747e49ac0'
server.IMAGES['node'] = 'node@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c'

class ExecutorTest(unittest.TestCase):
    def execute(self, script, extension='py', inputs=None, timeout=30, files=None):
        job = str(uuid.uuid4())
        path = 'scripts/main.' + extension
        server.JOBS[job] = {'status': 'STARTED'}
        encoded = lambda value: base64.b64encode(value.encode()).decode()
        package = {path: encoded(script), **{k: encoded(v) for k, v in (files or {}).items()}}
        server.run_job({'id': job, 'script': path, 'arguments': [], 'files': package, 'inputs': {k: encoded(v) for k, v in (inputs or {}).items()}, 'timeoutSeconds': timeout})
        return server.JOBS[job]

    def test_python_input_and_artifact(self):
        result = self.execute("from pathlib import Path\nPath('/output/report.txt').write_text(Path('/input/content.txt').read_text()+' verified')", inputs={'content.txt': '章节原文'})
        self.assertEqual('SUCCEEDED', result['status'], result)
        self.assertEqual('章节原文 verified', base64.b64decode(result['artifacts'][0]['base64']).decode())

    def test_node_artifact(self):
        result = self.execute("require('fs').writeFileSync('/output/report.json', JSON.stringify({ok:true}))", 'js')
        self.assertEqual('SUCCEEDED', result['status'], result)
        self.assertEqual(b'{"ok":true}', base64.b64decode(result['artifacts'][0]['base64']))

    def test_full_package_directories_and_assets(self):
        result = self.execute("from pathlib import Path\nassert Path('/skill/assets/empty').is_dir()\nassert Path('/skill/assets/info.txt').read_text()=='asset'", files={'assets/empty/': '', 'assets/info.txt': 'asset'})
        self.assertEqual('SUCCEEDED', result['status'], result)

    def test_nonzero_and_dependency(self):
        self.assertEqual('SCRIPT_FAILED', self.execute('raise RuntimeError("real failure")')['errorCode'])
        self.assertEqual('SKILL_DEPENDENCY_UNAVAILABLE', self.execute('import nonexistent_mojie_dependency')['errorCode'])

    def test_timeout(self):
        self.assertEqual('SCRIPT_TIMEOUT', self.execute('import time; time.sleep(20)', timeout=1)['errorCode'])

    def test_output_limit(self):
        self.assertEqual('SCRIPT_OUTPUT_LIMIT_EXCEEDED', self.execute('print("x" * 300000)')['errorCode'])

    def test_readonly_package_no_secrets_no_network(self):
        script = """import os, socket
from pathlib import Path
assert 'MOJIE_MODEL_API_KEY' not in os.environ
assert not Path('/var/run/docker.sock').exists()
assert not Path('/input/other').exists()
try:
 Path('/skill/scripts/main.py').write_text('changed')
 raise AssertionError('package writable')
except OSError: pass
s=socket.socket();s.settimeout(.2)
try:
 s.connect(('1.1.1.1',443))
 raise AssertionError('network accessible')
except OSError: pass
print('isolated')
"""
        result = self.execute(script)
        self.assertEqual('SUCCEEDED', result['status'], result)
        self.assertIn('isolated', result['stdout'])

    def test_output_link_rejected(self):
        result = self.execute("import os; os.symlink('/etc/passwd','/output/link')")
        self.assertEqual('SCRIPT_FAILED', result['errorCode'])

    def background_job(self, disconnected=False):
        job = str(uuid.uuid4())
        path = 'scripts/main.py'
        script = "import subprocess,time\nsubprocess.Popen(['python3','-c','import time; time.sleep(60)'])\ntime.sleep(60)"
        server.JOBS[job] = {'status': 'STARTED', 'lastSeen': time.monotonic() - (7 if disconnected else 0)}
        thread = threading.Thread(target=server.run_job, args=({'id': job, 'script': path,
            'arguments': [], 'files': {path: base64.b64encode(script.encode()).decode()},
            'inputs': {}, 'timeoutSeconds': 30},))
        thread.start()
        return job, thread

    def assert_stopped(self, job, thread):
        thread.join(10)
        self.assertFalse(thread.is_alive(), 'execution process did not stop')
        self.assertEqual('CANCELLED', server.JOBS[job]['errorCode'])
        self.assertEqual([], server.JOBS[job]['artifacts'])
        self.assertEqual('', server.docker('ps', '-aq', '--filter', 'name=mojie-skill-' + job).stdout.decode().strip())
        self.assertFalse((server.ROOT / job).exists())

    def test_cancel_stops_container_and_child_process(self):
        job, thread = self.background_job()
        try:
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                if server.docker('inspect', '-f', '{{.State.Running}}', 'mojie-skill-' + job).stdout.strip() == b'true':
                    break
                time.sleep(.05)
            else:
                self.fail('script container did not start')
            with server.LOCK:
                server.JOBS[job]['cancelled'] = True
            server.stop_container('mojie-skill-' + job)
            self.assert_stopped(job, thread)
        finally:
            server.stop_container('mojie-skill-' + job)
            thread.join(10)

    def test_lost_backend_poll_stops_container(self):
        job, thread = self.background_job()
        try:
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                if server.docker('inspect', '-f', '{{.State.Running}}', 'mojie-skill-' + job).stdout.strip() == b'true':
                    break
                time.sleep(.05)
            else:
                self.fail('script container did not start')
            with server.LOCK:
                server.JOBS[job]['lastSeen'] = time.monotonic() - 7
            self.assert_stopped(job, thread)
        finally:
            server.stop_container('mojie-skill-' + job)
            thread.join(10)

    def test_restart_only_stops_same_instance(self):
        names = ['mojie-recovery-' + str(uuid.uuid4()) for _ in range(2)]
        try:
            for name, label in zip(names, [server.INSTANCE, server.INSTANCE + '-other']):
                result = server.docker('run', '-d', '--rm', '--name', name, '--network=none',
                    '--label', 'mojie.skill-executor=' + label, '--pull=never',
                    server.IMAGES['python'], 'python3', '-c', 'import time; time.sleep(60)')
                self.assertEqual(0, result.returncode, result.stderr)
            server.cleanup_stale_containers()
            self.assertNotEqual(0, server.docker('inspect', names[0]).returncode)
            self.assertEqual(b'true', server.docker('inspect', '-f', '{{.State.Running}}', names[1]).stdout.strip())
        finally:
            for name in names:
                server.stop_container(name)

    def test_artifact_count_limit(self):
        result = self.execute("from pathlib import Path\nfor i in range(21): Path('/output/'+str(i)).write_text('x')")
        self.assertEqual('SCRIPT_OUTPUT_LIMIT_EXCEEDED', result['errorCode'])

    def test_artifact_file_size_limit(self):
        result = self.execute("from pathlib import Path\nPath('/output/oversized.bin').write_bytes(b'x'*(21*1024*1024))")
        self.assertEqual('SCRIPT_OUTPUT_LIMIT_EXCEEDED', result['errorCode'], result)

if __name__ == '__main__':
    unittest.main()
