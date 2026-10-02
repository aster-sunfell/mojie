"""Trusted execution coordinator. Never runs uploaded code in this process."""
import base64
import json
import hashlib
import os
import pathlib
import selectors
import shutil
import signal
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(os.environ.get('MOJIE_EXECUTOR_WORK_DIR', '/tmp/mojie-executor')).resolve()
ROOT.mkdir(parents=True, exist_ok=True)
IMAGES = {'python': os.environ.get('MOJIE_PYTHON_IMAGE', ''), 'node': os.environ.get('MOJIE_NODE_IMAGE', '')}
INSTANCE = hashlib.sha256(str(ROOT).encode()).hexdigest()[:16]
JOBS = {}
LOCK = threading.Lock()
OUTPUT_LIMIT = 256 * 1024


def safe_path(name):
    parts = name.split('/')
    if not name or name.startswith('/') or '\\' in name or '\0' in name or ':' in name or len(parts) > 16 or any(p in ('', '.', '..') for p in parts):
        raise ValueError('Invalid relative path')
    return pathlib.Path(*parts)


def docker(*args, timeout=10):
    return subprocess.run(['docker', *args], capture_output=True, timeout=timeout)


def stop_container(name):
    try:
        docker('rm', '-f', name)
    except Exception:
        pass


def cleanup_stale_containers():
    stale = docker('ps', '-aq', '--filter', 'label=mojie.skill-executor=' + INSTANCE)
    if stale.returncode:
        raise RuntimeError('Cannot inspect executor containers')
    for container in stale.stdout.decode().split():
        stop_container(container)


def run_job(payload):
    job_id = str(uuid.UUID(payload['id']))
    name = 'mojie-skill-' + job_id
    work = ROOT / job_id
    result = {'status': 'FAILED', 'exitCode': None, 'stdout': '', 'stderr': '', 'artifacts': []}
    process = None
    try:
        script = safe_path(payload['script'])
        runtime = 'python' if str(script).endswith('.py') else 'node' if str(script).endswith(('.js', '.mjs')) else None
        if runtime is None or not str(script).startswith('scripts/'):
            result['errorCode'] = 'SKILL_RUNTIME_UNAVAILABLE'
            return
        image = IMAGES[runtime]
        result['environment'] = {'runtime': runtime, 'image': image, 'network': False, 'cpus': 1, 'memoryMiB': 512, 'pids': 64, 'dependencies': ['standard library']}
        if '@sha256:' not in image:
            result['errorCode'] = 'SKILL_RUNTIME_UNAVAILABLE'
            result['stderr'] = 'Runtime image must be configured with a sha256 digest'
            return
        for folder in ('skill', 'input', 'output'):
            (work / folder).mkdir(parents=True)
        total = 0
        for folder, field in (('skill', 'files'), ('input', 'inputs')):
            entries = payload.get(field, {})
            if not isinstance(entries, dict) or len(entries) > 500:
                raise ValueError('Invalid file count')
            for path, encoded in entries.items():
                directory = folder == 'skill' and path.endswith('/')
                relative = safe_path(path[:-1] if directory else path)
                if folder == 'input' and len(relative.parts) != 1:
                    raise ValueError('Input names must be flat')
                data = base64.b64decode(encoded, validate=True)
                total += len(data)
                if len(data) > 20 * 1024**2 or total > 120 * 1024**2:
                    raise ValueError('Input limit exceeded')
                target = work / folder / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                if directory:
                    if data:
                        raise ValueError('Directory entries must have no contents')
                    target.mkdir(exist_ok=True)
                    continue
                target.write_bytes(data)
                target.chmod(0o444)
        if not (work / 'skill' / script).is_file():
            raise ValueError('Unknown script')
        (work / 'output').chmod(0o777)
        arguments = payload.get('arguments', [])
        if not isinstance(arguments, list) or len(arguments) > 64 or any(not isinstance(a, str) or len(a) > 4096 or '\0' in a for a in arguments):
            raise ValueError('Invalid arguments')
        interpreter = ['python3', '-B'] if runtime == 'python' else ['node']
        command = ['docker', 'create', '--name', name, '--label', 'mojie.skill-executor=' + INSTANCE, '--rm', '--pull=never', '--network=none', '--read-only', '--user=10001:10001', '--cap-drop=ALL', '--security-opt=no-new-privileges', '--pids-limit=64', '--memory=512m', '--memory-swap=512m', '--cpus=1', '--ulimit', 'fsize=20971520:20971520', '--tmpfs', '/tmp:rw,noexec,nosuid,size=32m', '-v', str(work / 'skill') + ':/skill:ro', '-v', str(work / 'input') + ':/input:ro', '-v', str(work / 'output') + ':/output:rw', '-w', '/output', image, *interpreter, '/skill/' + str(script), *arguments]
        with LOCK:
            if JOBS[job_id].get('cancelled') or ('lastSeen' in JOBS[job_id] and time.monotonic() - JOBS[job_id]['lastSeen'] > 6):
                result['errorCode'] = 'CANCELLED'
                return
        created = subprocess.run(command, capture_output=True, timeout=10)
        if created.returncode:
            result['errorCode'] = 'SKILL_RUNTIME_UNAVAILABLE'
            result['stderr'] = 'Isolated runtime container could not be created'
            return
        # Register the container before starting code: cancellation cannot miss an in-flight run.
        with LOCK:
            if JOBS[job_id].get('cancelled') or ('lastSeen' in JOBS[job_id] and time.monotonic() - JOBS[job_id]['lastSeen'] > 6):
                result['errorCode'] = 'CANCELLED'
                return
            process = subprocess.Popen(['docker', 'start', '--attach', name], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        selector = selectors.DefaultSelector()
        for stream in (process.stdout, process.stderr):
            os.set_blocking(stream.fileno(), False)
            selector.register(stream, selectors.EVENT_READ)
        buffers = {process.stdout: bytearray(), process.stderr: bytearray()}
        deadline = time.monotonic() + max(1, min(30, int(payload.get('timeoutSeconds', 30))))
        while selector.get_map():
            with LOCK:
                cancelled = JOBS[job_id].get('cancelled', False)
            with LOCK:
                disconnected = 'lastSeen' in JOBS[job_id] and time.monotonic() - JOBS[job_id]['lastSeen'] > 6
            if cancelled or disconnected or time.monotonic() >= deadline:
                result['errorCode'] = 'CANCELLED' if cancelled or disconnected else 'SCRIPT_TIMEOUT'
                stop_container(name)
                break
            outputs = list((work / 'output').rglob('*'))
            if len(outputs) > 500 or sum(p.lstat().st_size for p in outputs if not p.is_symlink() and p.is_file()) > 20 * 1024**2:
                result['errorCode'] = 'SCRIPT_OUTPUT_LIMIT_EXCEEDED'
                stop_container(name)
                break
            for key, _ in selector.select(.1):
                data = os.read(key.fileobj.fileno(), 65536)
                if not data:
                    selector.unregister(key.fileobj)
                else:
                    remaining = OUTPUT_LIMIT - sum(map(len, buffers.values()))
                    buffers[key.fileobj].extend(data[:max(0, remaining)])
                    if len(data) > remaining:
                        result['errorCode'] = 'SCRIPT_OUTPUT_LIMIT_EXCEEDED'
                        result['outputTruncated'] = True
                        stop_container(name)
                        break
            if result.get('errorCode'):
                break
        selector.close()
        result['exitCode'] = process.wait(timeout=5)
        result['stdout'] = buffers[process.stdout].decode('utf-8', errors='replace')
        result['stderr'] = buffers[process.stderr].decode('utf-8', errors='replace')
        with LOCK:
            if JOBS[job_id].get('cancelled') or ('lastSeen' in JOBS[job_id] and time.monotonic() - JOBS[job_id]['lastSeen'] > 6):
                result['errorCode'] = 'CANCELLED'
        if result.get('errorCode'):
            return
        if result['exitCode']:
            if result['exitCode'] == 128 + signal.SIGXFSZ or 'File too large' in result['stderr']:
                result['errorCode'] = 'SCRIPT_OUTPUT_LIMIT_EXCEEDED'
            else:
                result['errorCode'] = 'SKILL_DEPENDENCY_UNAVAILABLE' if ('ModuleNotFoundError' in result['stderr'] or 'Cannot find module' in result['stderr']) else 'SCRIPT_FAILED'
            return
        outputs = []
        total = 0
        for target in sorted((work / 'output').rglob('*')):
            if target.is_symlink():
                raise ValueError('Generated links are forbidden')
            if target.is_dir():
                continue
            if not target.is_file() or target.stat().st_nlink != 1:
                raise ValueError('Generated special files are forbidden')
            size = target.stat().st_size
            total += size
            if len(outputs) >= 20 or total > 20 * 1024**2:
                result['errorCode'] = 'SCRIPT_OUTPUT_LIMIT_EXCEEDED'
                return
            outputs.append({'name': str(target.relative_to(work / 'output')), 'base64': base64.b64encode(target.read_bytes()).decode(), 'mime': 'application/octet-stream'})
        result.update(status='SUCCEEDED', artifacts=outputs)
    except Exception as error:
        if not result.get('errorCode'):
            result['errorCode'] = 'SCRIPT_FAILED'
            result['stderr'] = 'Execution failed: ' + type(error).__name__
    finally:
        stop_container(name)
        if process is not None and process.poll() is None:
            process.kill()
            process.wait()
        if process is not None:
            process.stdout.close()
            process.stderr.close()
        shutil.rmtree(work, ignore_errors=True)
        with LOCK:
            JOBS[job_id].update(result)


class Handler(BaseHTTPRequestHandler):
    def reply(self, code, value):
        data = json.dumps(value).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        if self.path != '/executions':
            return self.reply(404, {})
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if size <= 0 or size > 170 * 1024**2:
                return self.reply(413, {})
            payload = json.loads(self.rfile.read(size))
            job_id = str(uuid.UUID(payload['id']))
            with LOCK:
                if job_id in JOBS:
                    return self.reply(200, JOBS[job_id])
                if sum(j['status'] == 'STARTED' for j in JOBS.values()) >= 2:
                    return self.reply(429, {})
                JOBS[job_id] = {'status': 'STARTED', 'lastSeen': time.monotonic()}
            threading.Thread(target=run_job, args=(payload,), daemon=True).start()
            return self.reply(202, {'id': job_id, 'status': 'STARTED'})
        except (ValueError, KeyError):
            return self.reply(400, {})

    def do_GET(self):
        if self.path == '/health':
            return self.reply(200, {'status': 'UP'})
        job_id = self.path.removeprefix('/executions/')
        with LOCK:
            if job_id in JOBS:
                JOBS[job_id]['lastSeen'] = time.monotonic()
            result = dict(JOBS.get(job_id, {}))
            result.pop('lastSeen', None)
        return self.reply(200 if result else 404, result)

    def do_DELETE(self):
        job_id = self.path.removeprefix('/executions/')
        try:
            job_id = str(uuid.UUID(job_id))
        except ValueError:
            return self.reply(400, {})
        with LOCK:
            if job_id in JOBS:
                JOBS[job_id]['cancelled'] = True
        stop_container('mojie-skill-' + job_id)
        return self.reply(200, {'status': 'CANCELLED'})

    def log_message(self, *_):
        pass


if __name__ == '__main__':
    # Stop containers from an interrupted coordinator; never resume uploaded code.
    cleanup_stale_containers()
    server = ThreadingHTTPServer((os.environ.get('MOJIE_EXECUTOR_BIND', '127.0.0.1'), 8091), Handler)
    def shutdown(*_):
        with LOCK:
            ids = list(JOBS)
            for job in JOBS.values():
                job['cancelled'] = True
        for job_id in ids:
            stop_container('mojie-skill-' + job_id)
        os._exit(0)
    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    server.serve_forever()
