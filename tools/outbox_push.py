#!/usr/bin/env python3
"""Claude용: 공지 문구·파일을 '수인T 카톡' 앱 공개 열쇠로 암호화해 GitHub outbox 가지에 올린다.
앱(선생님 폰)만 풀 수 있다. 실제 발송은 앱에서 선생님이 확인·전송해야만 일어난다.
사용: python3 tools/outbox_push.py --title "제목" --to "중3 단톡방" --text-file msg.txt [파일...]
      (tools/outbox_pubkey.txt 에 앱의 공개 열쇠가 있어야 함. 결과물은 outbox_out/ 에 생기고, 그 폴더를 outbox 가지에 커밋·푸시)
"""
import argparse, base64, json, os, time, uuid, mimetypes
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

HERE = os.path.dirname(os.path.abspath(__file__))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--title", required=True); ap.add_argument("--to", default=""); ap.add_argument("--text", default=""); ap.add_argument("--text-file")
    ap.add_argument("--out", default="outbox_out"); ap.add_argument("files", nargs="*")
    a = ap.parse_args()
    text = open(a.text_file, encoding="utf-8").read().strip() if a.text_file else a.text
    pub_b64 = open(os.path.join(HERE, "outbox_pubkey.txt")).read().strip().split(":")[-1]
    pub = serialization.load_der_public_key(base64.b64decode(pub_b64))
    key = AESGCM.generate_key(bit_length=256); g = AESGCM(key)
    os.makedirs(a.out, exist_ok=True)
    iid = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
    files = []
    for n, path in enumerate(a.files):
        raw = open(path, "rb").read(); iv = os.urandom(12)
        blob = f"{iid}-{n}.bin"
        open(os.path.join(a.out, blob), "wb").write(g.encrypt(iv, raw, None))
        files.append({"name": os.path.basename(path), "mime": mimetypes.guess_type(path)[0] or "application/octet-stream",
                      "size": len(raw), "blob": blob, "iv": base64.b64encode(iv).decode()})
    payload = {"title": a.title, "to": a.to, "text": text, "files": files, "createdAt": int(time.time() * 1000), "status": "ready", "from": "Claude"}
    iv = os.urandom(12)
    env = {"v": 1,
           "ek": base64.b64encode(pub.encrypt(key, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))).decode(),
           "iv": base64.b64encode(iv).decode(),
           "ct": base64.b64encode(g.encrypt(iv, json.dumps(payload, ensure_ascii=False).encode(), None)).decode()}
    json.dump(env, open(os.path.join(a.out, iid + ".json"), "w"))
    print(iid)

if __name__ == "__main__":
    main()
