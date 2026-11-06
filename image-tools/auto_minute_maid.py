import subprocess, time, json, urllib.request, socket, base64, os, struct

def send_ws(s, cmd):
    msg = json.dumps(cmd).encode('utf-8')
    length = len(msg)
    if length <= 125:
        hdr = bytearray([0x81, 0x80 | length])
    elif length <= 65535:
        hdr = bytearray([0x81, 0x80 | 126]) + struct.pack('>H', length)
    else:
        hdr = bytearray([0x81, 0x80 | 127]) + struct.pack('>Q', length)
    mask = os.urandom(4)
    hdr.extend(mask)
    masked = bytearray(b ^ mask[i % 4] for i, b in enumerate(msg))
    s.sendall(hdr + masked)

revealed_pages = set()

def check_and_reveal():
    try:
        res = subprocess.run(['adb', 'shell', 'cat', '/proc/net/unix'], capture_output=True, text=True, timeout=2)
        lines = [l for l in res.stdout.splitlines() if '@webview_devtools_remote_' in l]
        if lines:
            sock = lines[0].split('@')[1].strip()
            subprocess.run(['adb', 'forward', 'tcp:9222', f'localabstract:{sock}'], capture_output=True, timeout=2)
    except Exception:
        pass

    try:
        with urllib.request.urlopen('http://127.0.0.1:9222/json/list', timeout=1) as r:
            pages = json.loads(r.read().decode('utf-8'))
        
        for p in pages:
            url = p.get('url', '')
            page_id = p.get('id', '')
            if (page_id, url) in revealed_pages:
                continue
            if 'accounts.google.com' in url or 'signin' in url or 'embedded/setup' in url:
                ws_url = p.get('webSocketDebuggerUrl')
                if not ws_url: continue
                path = ws_url.split('9222')[1]
                s = socket.socket()
                s.settimeout(2)
                s.connect(('127.0.0.1', 9222))
                key = base64.b64encode(os.urandom(16)).decode('utf-8')
                req = (
                    f"GET {path} HTTP/1.1\r\n"
                    "Host: 127.0.0.1:9222\r\n"
                    "Upgrade: websocket\r\n"
                    "Connection: Upgrade\r\n"
                    f"Sec-WebSocket-Key: {key}\r\n"
                    "Sec-WebSocket-Version: 13\r\n\r\n"
                )
                s.sendall(req.encode('utf-8'))
                s.recv(1024)
                
                # Immediate execution + self-clearing interval observer inside the page
                expr = """
                (function() {
                    function tryShow() {
                        if (window.mm && typeof window.mm.showView === 'function') {
                            window.mm.showView();
                            return true;
                        }
                        return false;
                    }
                    if (!tryShow()) {
                        var attempts = 0;
                        var timer = setInterval(function() {
                            attempts++;
                            if (tryShow() || attempts > 100) {
                                clearInterval(timer);
                            }
                        }, 100);
                    }
                    return 'OBSERVER_INSTALLED';
                })()
                """
                send_ws(s, {"id": 1, "method": "Runtime.evaluate", "params": {"expression": expr}})
                resp_data = s.recv(4096)
                print(f"[auto_minute_maid] Injected showView observer for page {page_id}: {resp_data}")
                revealed_pages.add((page_id, url))
                time.sleep(0.1)
                s.close()
    except Exception:
        pass

def main():
    print("[auto_minute_maid] Daemon started...")
    while True:
        check_and_reveal()
        time.sleep(1.0)

if __name__ == '__main__':
    main()
