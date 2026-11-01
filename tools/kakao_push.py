#!/usr/bin/env python3
"""Claude용: 공지 문구와 파일을 '수인T 카톡' 앱의 보낼 함(kakaoOutbox)에 넣는다. 보내지는 않는다.
사용: FB_EMAIL=... FB_PASSWORD=... python3 kakao_push.py --title "제목" --to "중3 단톡방" --text-file msg.txt file1.pdf file2.pdf
"""
import argparse, base64, json, mimetypes, os, sys, time, urllib.request, uuid

DB = "https://suin-attend-default-rtdb.asia-southeast1.firebasedatabase.app"
HERE = os.path.dirname(os.path.abspath(__file__))
KEY = json.load(open(os.path.join(HERE, "..", "app", "google-services.json")))["client"][0]["api_key"][0]["current_key"]
CHUNK = 3 * 1024 * 1024  # 원본 3MB → base64 4MB (RTDB 문자열 한도 10MB 아래)

def req(method, url, body=None):
    data = None if body is None else json.dumps(body).encode()
    r = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(r, timeout=120) as f:
        return json.loads(f.read().decode() or "null")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--title", required=True); ap.add_argument("--to", default=""); ap.add_argument("--text", default=""); ap.add_argument("--text-file")
    ap.add_argument("files", nargs="*")
    a = ap.parse_args()
    text = open(a.text_file, encoding="utf-8").read().strip() if a.text_file else a.text
    tok = req("POST", f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key={KEY}",
              {"email": os.environ["FB_EMAIL"], "password": os.environ["FB_PASSWORD"], "returnSecureToken": True})["idToken"]
    files = []
    for path in a.files:
        raw = open(path, "rb").read(); fid = uuid.uuid4().hex
        parts = [raw[i:i + CHUNK] for i in range(0, len(raw), CHUNK)] or [b""]
        for k, p in enumerate(parts):
            req("PUT", f"{DB}/kakaoFiles/{fid}/{k}.json?auth={tok}", base64.b64encode(p).decode())
        files.append({"id": fid, "name": os.path.basename(path), "mime": mimetypes.guess_type(path)[0] or "application/octet-stream",
                      "size": len(raw), "chunks": len(parts)})
        print("uploaded", path, len(raw), "bytes,", len(parts), "chunks", file=sys.stderr)
    item = {"title": a.title, "to": a.to, "text": text, "files": files, "createdAt": int(time.time() * 1000), "status": "ready", "from": "Claude"}
    r = req("POST", f"{DB}/kakaoOutbox.json?auth={tok}", item)
    print("queued", r.get("name"))

if __name__ == "__main__":
    main()
