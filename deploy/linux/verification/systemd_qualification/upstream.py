"""Synthetic upstream for the fixed isolated systemd qualification."""
import argparse
from http.server import BaseHTTPRequestHandler,HTTPServer
import json
import os
from pathlib import Path
import signal
import ssl
import threading


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--config',required=True)
    args=parser.parse_args();config=json.loads(Path(args.config).read_text())
    if set(config)!={'fixtureKind','identityPort','appPort','runId'} or config['fixtureKind']!='systemd-proxy-qualification':
        raise RuntimeError('Exact isolated fixture configuration required')
    ports=[config['identityPort'],config['appPort']]
    if any(type(p) is not int or not 1024<p<65536 for p in ports) or len(set(ports))!=2:
        raise RuntimeError('Two reviewed distinct loopback high ports required')
    credentials=Path(os.environ['CREDENTIALS_DIRECTORY'])
    if str(credentials)!='/run/credentials/ols-tls-qualification-upstream.service':
        raise RuntimeError('Exact test-unit credential directory required')
    stop=threading.Event();servers=[];threads=[]
    signal.signal(signal.SIGTERM,lambda *args:stop.set())
    signal.signal(signal.SIGINT,lambda *args:stop.set())
    class FixtureServer(HTTPServer):
        def get_request(self):
            connection,address=self.socket.accept()
            try:
                connection.settimeout(3);connection.do_handshake();return connection,address
            except Exception:
                connection.close();raise
    def handler(role):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass  # Never log tokens, cookies or bodies.
            def answer(self):
                length=int(self.headers.get('Content-Length','0'))
                if not 0<=length<=65536:
                    self.send_error(413);return
                if length:self.rfile.read(length)
                data={'fixtureKind':'systemd-proxy-qualification','runId':config['runId'],
                      'upstream':role,'method':self.command,'path':self.path,'host':self.headers.get('Host')}
                if self.path.endswith('/certs'):data['keys']=[]
                if self.path.endswith('/token/introspect'):data['active']=False
                body=json.dumps(data).encode()
                self.send_response(200);self.send_header('Content-Type','application/json')
                self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
            do_GET=answer
            do_POST=answer
        return Handler
    try:
        for role,port in zip(('identity','app'),ports):
            server=FixtureServer(('127.0.0.1',port),handler(role));servers.append(server)
            context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.minimum_version=ssl.TLSVersion.TLSv1_3
            context.load_cert_chain(credentials/(role+'.crt'),credentials/(role+'.key'))
            server.socket=context.wrap_socket(server.socket,server_side=True,do_handshake_on_connect=False)
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start();threads.append(thread)
        stop.wait()
    finally:
        for server,thread in zip(servers,threads):server.shutdown();server.server_close();thread.join()
        for server in servers[len(threads):]:server.server_close()


if __name__=='__main__':main()
