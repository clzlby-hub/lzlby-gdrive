import http.server
import socketserver
import urllib.parse
import urllib.request
import json
import subprocess
import sys
import threading
import time
import os
import argparse

# Configurable options via CLI flags, environment variables, or default placeholders
DEFAULT_CLIENT_ID = os.environ.get("GOOGLE_CLIENT_ID", "YOUR_CLIENT_ID_HERE.apps.googleusercontent.com")
DEFAULT_CLIENT_SECRET = os.environ.get("GOOGLE_CLIENT_SECRET", "YOUR_CLIENT_SECRET_HERE")
DEFAULT_PORT = 8085
DEFAULT_TV_IP = os.environ.get("TV_IP", "192.168.1.100")
DEFAULT_TV_PORT = os.environ.get("TV_ADB_PORT", "5555")
DEFAULT_PROXY = os.environ.get("HTTP_PROXY", None)

parser = argparse.ArgumentParser(description="Google Drive TV OAuth & ADB Helper")
parser.add_argument("--client-id", default=DEFAULT_CLIENT_ID, help="Google Cloud OAuth Client ID")
parser.add_argument("--client-secret", default=DEFAULT_CLIENT_SECRET, help="Google Cloud OAuth Client Secret")
parser.add_argument("--tv-ip", default=DEFAULT_TV_IP, help="Target Android TV IP address")
parser.add_argument("--tv-port", default=DEFAULT_TV_PORT, help="Target Android TV ADB port")
parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Local OAuth callback server port")
parser.add_argument("--proxy", default=DEFAULT_PROXY, help="HTTP/HTTPS proxy URL (e.g. http://127.0.0.1:7890)")

args, _ = parser.parse_known_args()

CLIENT_ID = args.client_id
CLIENT_SECRET = args.client_secret
PORT = args.port
REDIRECT_URI = f"http://127.0.0.1:{PORT}/"
TV_IP = args.tv_ip
TV_ADB_PORT = args.tv_port

if args.proxy:
    proxy_handler = urllib.request.ProxyHandler({
        'http': args.proxy,
        'https': args.proxy
    })
    opener = urllib.request.build_opener(proxy_handler)
else:
    opener = urllib.request.build_opener()

auth_code = None

class OAuthCallbackHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        global auth_code
        parsed = urllib.parse.urlparse(self.path)
        params = urllib.parse.parse_qs(parsed.query)

        if "code" in params:
            auth_code = params["code"][0]
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            html = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"><title>授权成功</title>
            <style>body{background:#0f172a;color:#f8fafc;font-family:sans-serif;text-align:center;padding:50px;}</style></head>
            <body>
                <h1 style="color:#34d399;">🎉 Google 授权成功！</h1>
                <p style="font-size:18px;">正在自动将凭证同步至电视相册，您可以关闭此网页并查看电视了！</p>
            </body>
            </html>
            """
            self.wfile.write(html.encode("utf-8"))
            threading.Thread(target=finish_auth).start()
        else:
            self.send_response(400)
            self.end_headers()
            self.wfile.write(b"No code received")

    def log_message(self, format, *args):
        pass

def finish_auth():
    global auth_code
    print(f"\n[+] 收到授权码 (Auth Code): {auth_code[:10]}...")
    token_url = "https://oauth2.googleapis.com/token"
    data = urllib.parse.urlencode({
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
        "code": auth_code,
        "grant_type": "authorization_code",
        "redirect_uri": REDIRECT_URI
    }).encode("utf-8")

    req = urllib.request.Request(token_url, data=data, method="POST")
    try:
        resp = opener.open(req, timeout=15)
        tokens = json.loads(resp.read().decode("utf-8"))
        access_token = tokens.get("access_token", "")
        refresh_token = tokens.get("refresh_token", "")
        expires_in = tokens.get("expires_in", 3600)
        expiry_time = int(time.time() * 1000) + (expires_in * 1000)

        print(f"[+] Access Token 获取成功: {access_token[:15]}...")
        print(f"[+] Refresh Token 获取成功: {refresh_token[:15]}...")

        prefs_xml = f"""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="client_id">{CLIENT_ID}</string>
    <string name="client_secret">{CLIENT_SECRET}</string>
    <string name="access_token">{access_token}</string>
    <string name="refresh_token">{refresh_token}</string>
    <long name="token_expiry" value="{expiry_time}" />
    <string name="screensaver_folder_id">root</string>
    <string name="screensaver_folder_name">全部相册</string>
    <int name="slideshow_interval" value="6" />
</map>"""

        cmd_push = f"adb -s {TV_IP}:{TV_ADB_PORT} shell 'cat > /sdcard/gdrive_prefs.xml' << 'EOF'\n{prefs_xml}\nEOF"
        subprocess.run(cmd_push, shell=True, check=True)
        run_as_cmd = f"adb -s {TV_IP}:{TV_ADB_PORT} shell \"run-as org.moontechlab.gdrivetv cp /sdcard/gdrive_prefs.xml /data/data/org.moontechlab.gdrivetv/shared_prefs/gdrive_tv_prefs.xml\""
        subprocess.run(run_as_cmd, shell=True)

        print("\n" + "="*60)
        print("🎉 您的 Refresh Token (长期有效，请妥善保存):")
        print(refresh_token)
        print("="*60 + "\n")
        sys.stdout.flush()

        print("[+] 正在发送同步 Intent 到电视应用...")
        cmd_start = (
            f"adb -s {TV_IP}:{TV_ADB_PORT} shell am start -n org.moontechlab.gdrivetv/.ui.MainActivity "
            f"--es access_token '{access_token}' --es refresh_token '{refresh_token}' "
            f"--el token_expiry {expiry_time} --es client_id '{CLIENT_ID}' --es client_secret '{CLIENT_SECRET}'"
        )
        subprocess.run(cmd_start, shell=True)
        print("[+] 完成！请查看电视。")
        sys.exit(0)
    except Exception as e:
        print(f"[-] Token 交换错误: {e}", file=sys.stderr)
        sys.exit(1)

def run():
    if "YOUR_CLIENT_ID" in CLIENT_ID:
        print("❌ 错误: 请先配置您的 Google Client ID！")
        print("用法: python3 auth_helper.py --client-id <ID> --client-secret <SECRET> --tv-ip <TV_IP>")
        sys.exit(1)

    server = socketserver.TCPServer(("127.0.0.1", PORT), OAuthCallbackHandler)
    params = {
        "client_id": CLIENT_ID,
        "redirect_uri": REDIRECT_URI,
        "response_type": "code",
        "scope": "https://www.googleapis.com/auth/drive",
        "access_type": "offline",
        "prompt": "consent"
    }
    auth_url = "https://accounts.google.com/o/oauth2/v2/auth?" + urllib.parse.urlencode(params)
    print("\n" + "="*60)
    print("👉 请在浏览器中打开以下链接进行 Google 账号授权：")
    print(auth_url)
    print("="*60 + "\n")

    if sys.platform == "darwin":
        subprocess.run(["open", auth_url])
    elif sys.platform == "linux":
        subprocess.run(["xdg-open", auth_url])

    while auth_code is None:
        server.handle_request()

if __name__ == "__main__":
    run()
