"""Loopback-only MCP protocol fixture for SDK/API integration tests; not a production connector."""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass
    def do_GET(self):
        self.send_response(405)
        self.end_headers()
    def do_DELETE(self):
        self.send_response(200)
        self.end_headers()
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if 'id' not in request:
            self.send_response(202)
            self.end_headers()
            return
        method = request['method']
        result = {
            'initialize': {'protocolVersion': '2025-11-25', 'capabilities': {'tools': {'listChanged': False}, 'resources': {'subscribe': False, 'listChanged': False}}, 'serverInfo': {'name': 'studyflow-local-fixture', 'version': '1.0'}},
            'tools/list': {'tools': [{'name': 'lookup', 'description': 'Read local test reference', 'inputSchema': {'type': 'object', 'properties': {'query': {'type': 'string'}}, 'required': ['query'], 'additionalProperties': False}}]},
            'tools/call': {'content': [{'type': 'text', 'text': '外部测试资料应先导入知识库，再通过来源核验进行引用。'}], 'isError': False},
            'resources/list': {'resources': [{'uri': 'kb://fixture/source', 'name': '外部引用测试资料', 'mimeType': 'text/plain'}]},
            'resources/read': {'contents': [{'uri': 'kb://fixture/source', 'mimeType': 'text/plain', 'text': '外部测试资料应先导入知识库，再通过来源核验进行引用。'}]}
        }.get(method, {})
        body = json.dumps({'jsonrpc': '2.0', 'id': request['id'], 'result': result}, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8766)
    args = parser.parse_args()
    ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()
