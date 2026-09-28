"""One-run local Anthropic Messages fixture for manual API/SSE integration checks."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import re


class Handler(BaseHTTPRequestHandler):
    calls = 0

    def do_POST(self):
        if self.path != "/v1/messages":
            self.send_error(404)
            return
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        Handler.calls += 1
        if Handler.calls == 1:
            user = body["messages"][0]["content"]
            text = "".join(block.get("text", "") for block in user) if isinstance(user, list) else user
            chapter = re.search(r"目标章节 ID: ([0-9a-f-]+)", text).group(1)
            content = [{"type": "tool_use", "id": "tool-read-1", "name": "read_chapter",
                        "input": {"chapterId": chapter}}]
            stop = "tool_use"
        else:
            content = [{"type": "text", "text": "已完整读取目标章节。根据当前证据，尚不能确认设定矛盾。"}]
            stop = "end_turn"
        payload = json.dumps({"id": f"msg_test_{Handler.calls}", "type": "message", "role": "assistant",
                              "model": body["model"], "content": content, "stop_reason": stop,
                              "stop_sequence": None, "usage": {"input_tokens": 100, "output_tokens": 50}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)
        self.close_connection = True

    def log_message(self, format, *args):
        pass


HTTPServer(("127.0.0.1", 18082), Handler).serve_forever()
