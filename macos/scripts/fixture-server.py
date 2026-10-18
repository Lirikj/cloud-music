"""Local-only fixtures for bounded reads, Yandex pagination, downloads and cancellation."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs
import json, time
DATA=(b'CLOUDMUSIC-TEST-'*9000)[:65536]
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        u=urlsplit(self.path); q=parse_qs(u.query)
        if u.path.endswith('/public/resources'):
            if q.get('path')==['/Album']:
                items=[{'type':'file','name':f'Artist - Nested {i}.mp3','path':f'/Album/{i}.mp3','size':100} for i in range(3)]
                name='Album'
            else:
                items=[{'type':'file','name':f'Artist - Track {i}.mp3','path':f'/{i}.mp3','size':100} for i in range(205)]
                items += [{'type':'dir','name':'Album','path':'/Album'},{'type':'file','name':'cover.jpg','path':'/cover.jpg'}];name='Fixture'
            off=int(q.get('offset',['0'])[0]); data=json.dumps({'type':'dir','name':name,'_embedded':{'total':len(items),'items':items[off:off+200]}}).encode();self.respond(data,'application/json');return
        if u.path.endswith('/public/resources/download'):
            self.respond(json.dumps({'href':'https://example.invalid/audio.mp3'}).encode(),'application/json');return
        if u.path=='/denied':
            self.send_response(403);self.end_headers();return
        if u.path in ['/ignore-range','/download','/slow']:
            data=DATA if u.path=='/download' else DATA*16
            self.send_response(200);self.send_header('Content-Type','audio/mpeg');self.send_header('Content-Length',str(len(data)));self.end_headers()
            try:
                for start in range(0,len(data),4096):
                    self.wfile.write(data[start:start+4096]);self.wfile.flush()
                    if u.path=='/slow':time.sleep(.02)
            except (ConnectionError,BrokenPipeError): pass
            return
        self.send_response(404);self.end_headers()
    def respond(self,data,kind):
        self.send_response(200);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
if __name__=='__main__':
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
    print(server.server_address[1],flush=True)
    server.serve_forever()
