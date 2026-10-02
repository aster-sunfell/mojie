"""Compare two backend JARs against an isolated PostgreSQL test database. No real model requests.

Requires MOJIE_TEST_JDBC_URL, MOJIE_TEST_DB_USER and MOJIE_TEST_DB_PASSWORD.
Starts local API fixtures on 18083/18084, captures normalized responses and drops its schemas.
"""
import os, json, re, time, uuid, threading, subprocess, urllib.request, urllib.error, urllib.parse, pathlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class Provider(BaseHTTPRequestHandler):

    def log_message(self, *a):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        blocks = [block for m in body['messages'] for block in m['content']]
        if any((block.get('type') == 'tool_result' for block in blocks)):
            content = [{'type': 'text', 'text': '已完整读取目标章节；只读核查结束。'}]
            stop = 'end_turn'
        else:
            text = next((x['text'] for x in blocks if x.get('type') == 'text'))
            chapter = re.search('目标章节 ID: ([0-9a-f-]+)', text)[1]
            content = [{'type': 'tool_use', 'id': 'contract-read', 'name': 'read_chapter', 'input': {'chapterId': chapter}}]
            stop = 'tool_use'
        reply = json.dumps({'id': 'contract-message', 'type': 'message', 'role': 'assistant', 'model': body['model'], 'content': content, 'stop_reason': stop, 'stop_sequence': None, 'usage': {'input_tokens': 20, 'output_tokens': 20}}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(reply)))
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(reply)
        self.close_connection = True
provider = ThreadingHTTPServer(('127.0.0.1', 0), Provider)
threading.Thread(target=provider.serve_forever, daemon=True).start()
import argparse
parser = argparse.ArgumentParser(description='Compare baseline and refactored HTTP/SSE contracts using isolated schemas and a local model.')
parser.add_argument('--baseline-jar', required=True, type=pathlib.Path)
parser.add_argument('--candidate-jar', type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1] / 'backend/target/mojie-agent-core-0.1.0-SNAPSHOT-exec.jar')
parser.add_argument('--output', required=True, type=pathlib.Path)
parser.add_argument('--postgres-container', default='deploy-postgres-1')
args = parser.parse_args()
url = os.environ.get('MOJIE_TEST_JDBC_URL', '')
database = urllib.parse.urlsplit(url.removeprefix('jdbc:')).path.strip('/')
if database in {'', 'mojie', 'postgres'} or not re.fullmatch('[a-zA-Z0-9_]+', database):
    parser.error('MOJIE_TEST_JDBC_URL must name a dedicated disposable PostgreSQL database')
for jar in [args.baseline_jar, args.candidate_jar]:
    if not jar.is_file():
        parser.error('Executable JAR not found: ' + str(jar))
if args.output.exists():
    parser.error('Output already exists; choose a new evidence file')
procs = []
schemas = []
fixtures = {}
errors = []

def sql(command):
    subprocess.run(['docker', 'exec', args.postgres_container, 'psql', '-U', os.environ['MOJIE_TEST_DB_USER'], '-d', database, '-c', command], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True)

def scenario(name, port, jar):
    schema = 'mojie_contract_' + uuid.uuid4().hex
    schemas.append(schema)
    sql('CREATE SCHEMA ' + schema)
    env = dict(os.environ)
    env.update(MOJIE_DB_URL=url + ('&' if '?' in url else '?') + 'currentSchema=' + schema, MOJIE_DB_USER=os.environ['MOJIE_TEST_DB_USER'], MOJIE_DB_PASSWORD=os.environ['MOJIE_TEST_DB_PASSWORD'], MOJIE_PORT=str(port), MOJIE_MODEL_API_KEY='contract-test-key', MOJIE_MODEL_BASE_URL='http://127.0.0.1:' + str(provider.server_port), MOJIE_MODEL_API_PATH='/v1/messages', MOJIE_MODEL_ID='contract-test', MOJIE_MODEL_CONTEXT_TOKENS='200000', MOJIE_DEV_TRACE='true', MOJIE_OWNER_ID='contract-owner')
    log = open('/tmp/mojie-http-' + name + '.log', 'w')
    proc = subprocess.Popen(['java', '-jar', jar], env=env, stdout=log, stderr=subprocess.STDOUT)
    procs.append(proc)
    base = 'http://127.0.0.1:' + str(port) + '/api/v1'
    observed = {}

    def request(method, path, body=None, headers=None):
        req = urllib.request.Request(base + urllib.parse.quote(path, safe='/?=&:%'), None if body is None else json.dumps(body, ensure_ascii=False).encode(), method=method, headers={'Content-Type': 'application/json', **(headers or {})})
        try:
            r = urllib.request.urlopen(req, timeout=15)
        except urllib.error.HTTPError as error:
            r = error
        payload = r.read()
        return {'status': r.status, 'body': json.loads(payload) if payload else None}

    def capture(label, method, path, body=None, headers=None):
        value = request(method, path, body, headers)
        observed[label] = value
        return (value.get('body') or {}).get('data')
    for attempt in range(300):
        if proc.poll() is not None:
            raise RuntimeError(name + ' backend exited; see its log')
        try:
            if request('GET', '/health')['body']['data']['status'] == 'UP':
                break
        except Exception:
            pass
        time.sleep(0.1)
    else:
        raise RuntimeError(name + ' backend failed readiness')
    capture('health', 'GET', '/health')
    capture('capabilities', 'GET', '/system/capabilities')
    project = capture('createProject', 'POST', '/projects', {'title': '契约作品'})['id']
    chapter = capture('createChapter', 'POST', '/projects/' + project + '/chapters', {'title': '目标章'})['id']
    capture('saveChapter', 'PUT', '/chapters/' + chapter + '/content', {'content': '甲\r\n乙😀\r尾', 'expectedVersion': 1})
    capture('sameContent', 'PUT', '/chapters/' + chapter + '/content', {'content': '甲\n乙😀\n尾', 'expectedVersion': 2})
    capture('chapter', 'GET', '/chapters/' + chapter)
    capture('renameChapter', 'PATCH', '/chapters/' + chapter, {'title': '章节新名'})
    capture('revisions', 'GET', '/chapters/' + chapter + '/revisions')
    capture('revision', 'GET', '/chapters/' + chapter + '/revisions/2')
    capture('chapters', 'GET', '/projects/' + project + '/chapters?limit=1')
    capture('projects', 'GET', '/projects?keyword=契约&limit=1')
    capture('project', 'GET', '/projects/' + project)
    setting = capture('createSetting', 'POST', '/projects/' + project + '/settings', {'type': 'CHARACTER', 'title': '甲', 'content': '甲不会游泳。'})['id']
    capture('settings', 'GET', '/projects/' + project + '/settings?type=CHARACTER')
    capture('updateSetting', 'PATCH', '/settings/' + setting, {'type': 'WORLD', 'title': '河流', 'content': '河流湍急。'})
    capture('versionConflict', 'PUT', '/chapters/' + chapter + '/content', {'content': '过期版本', 'expectedVersion': 1})
    capture('invalidCursor', 'GET', '/projects?cursor=invalid')
    capture('missingChapter', 'GET', '/chapters/' + str(uuid.uuid4()))
    capture('missingProposal', 'GET', '/edit-proposals/' + str(uuid.uuid4()))
    runbody = {'projectId': project, 'targetChapterId': chapter, 'expectedVersion': 2, 'instruction': '只读核查', 'clientRequestId': str(uuid.uuid4())}
    run = capture('submit', 'POST', '/agent/runs', runbody)['runId']
    for _ in range(200):
        result = request('GET', '/agent/runs/' + run)
        if result['body']['data']['status'] in ['SUCCEEDED', 'FAILED', 'CANCELLED']:
            break
        time.sleep(0.05)
    if result['body']['data']['status'] != 'SUCCEEDED':
        raise RuntimeError(name + ' fixture run failed: ' + str(result))
    capture('runDetail', 'GET', '/agent/runs/' + run)
    capture('runList', 'GET', '/agent/runs?projectId=' + project)
    capture('trace', 'GET', '/agent/runs/' + run + '/trace')
    capture('idempotentRetry', 'POST', '/agent/runs', runbody)
    capture('idempotencyConflict', 'POST', '/agent/runs', {**runbody, 'instruction': '另一请求'})
    capture('terminalCancel', 'POST', '/agent/runs/' + run + '/cancel')
    capture('invalidEventCursor', 'GET', '/agent/runs/' + run + '/events?afterSeq=-1')
    for (label, path, headers) in [('eventReplay', '/agent/runs/' + run + '/events?afterSeq=0', {}), ('lastEventIdPrecedence', '/agent/runs/' + run + '/events?afterSeq=0', {'Last-Event-ID': '6'})]:
        with urllib.request.urlopen(urllib.request.Request(base + path, headers=headers), timeout=15) as response:
            stream = response.read().decode()
            events = [json.loads(line[5:]) for line in stream.splitlines() if line.startswith('data:')]
            observed[label] = {'status': response.status, 'body': events}
    capture('deleteSetting', 'DELETE', '/settings/' + setting)
    fixtures[name] = observed
try:
    scenario('baseline', 18083, str(args.baseline_jar))
    scenario('candidate', 18084, str(args.candidate_jar))

    def normalize(value, identities):
        if isinstance(value, dict):
            return {k: normalize(v, identities) for (k, v) in value.items()}
        if isinstance(value, list):
            return [normalize(v, identities) for v in value]
        if isinstance(value, str):
            if re.fullmatch('\\d{4}-\\d\\d-\\d\\dT[\\d:.]+(?:Z|[+-]\\d\\d:\\d\\d)', value):
                return '<timestamp>'

            def ident(m):
                return identities.setdefault(m[0], '<id-' + str(len(identities) + 1) + '>')
            return re.sub('[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', ident, value)
        return value
    normalized = {name: normalize(value, {}) for (name, value) in fixtures.items()}
    for (case, value) in normalized['baseline'].items():
        if value != normalized['candidate'][case]:
            errors.append(case)
    args.output.write_text(json.dumps({'cases': len(fixtures['baseline']), 'differences': errors, 'normalizedSamples': normalized}, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'cases': len(fixtures['baseline']), 'differences': errors}))
finally:
    for proc in procs:
        proc.terminate()
    for proc in procs:
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
    provider.shutdown()
    for schema in schemas:
        sql('DROP SCHEMA ' + schema + ' CASCADE')
if errors:
    raise SystemExit(1)
