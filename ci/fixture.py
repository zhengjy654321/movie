from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading

class Fixture(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/nodes.yaml':
            body = ('proxies:\n' + ''.join(
                f'  - {{name: fixture-{name}, type: http, server: 10.0.2.2, port: {port}}}\n'
                for name, port in [('a', 18981), ('b', 18982)])).encode()
        else:
            body = b'fixture-b-ok\n' if self.server.server_port == 18982 else b'fixture-a-ok\n'
        self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_CONNECT(self):
        self.send_response(200, 'Connection established')
        self.end_headers()
        self.close_connection = False

for port in (18980, 18981):
    server = ThreadingHTTPServer(('0.0.0.0', port), Fixture)
    threading.Thread(target=server.serve_forever, daemon=True).start()
ThreadingHTTPServer(('0.0.0.0', 18982), Fixture).serve_forever()
