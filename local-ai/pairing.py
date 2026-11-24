"""大D的主機：裝置配對。

App 不用再手動貼 43 字元的存取碼：
- 密碼配對：使用者在主機設一個好記的密碼（App 或 `server.py --set-password`），新裝置輸入一次密碼
  → 主機發給這台裝置自己的長存取碼（App 存起來，之後都用它）。密碼只在配對時傳一次。
- QR 配對：在主機（或家裡的 Wi-Fi）打開 http://<主機>:8765/pair，用 iPhone / iPad 相機掃 →
  QR 裡是 dylan://pair?url=…&code=…（一次性、10 分鐘有效的配對碼）→ App 用它換到自己的長存取碼。
- 每台裝置一個存取碼（主機只存雜湊），可以在 App 的裝置清單個別取消；原本的主存取碼（config.json 的 token）照樣有效。
- 管理者：主存取碼、第一台配對的裝置、以及加這個功能前就配對好的裝置。只有管理者能換模型、改配對密碼、
  看全部裝置、取消別台、指定別台當管理者；一般裝置只看得到、只能取消自己。多人共用一台主機時，
  別人不會一換模型就影響所有人。

防猜：配對失敗同一個 IP 5 次封 1 小時；全部來源 1 小時內失敗 30 次就暫停配對 1 小時。
"""
import hashlib
import hmac
import html
import io
import secrets
import threading
import time
import urllib.parse

PBKDF2_ROUNDS = 200_000
MIN_PASSWORD = 8
CODE_SECONDS = 600            # QR 配對碼有效 10 分鐘
PAIR_MAX_FAILS = 5            # 同一個 IP 錯幾次就封
PAIR_BLOCK = 3600
GLOBAL_MAX_FAILS = 30         # 全部來源一小時內錯幾次就暫停配對
SEEN_SAVE_EVERY = 600         # 「最後使用時間」最多 10 分鐘寫一次檔


def _hash_password(password, salt_hex):
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), bytes.fromhex(salt_hex), PBKDF2_ROUNDS).hex()


def _hash_token(token):
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


class Pairing:
    def __init__(self, cfg, save, log):
        self.cfg = cfg            # config.json 的內容（dict，會直接改）
        self.save = save          # 存回 config.json
        self.log = log
        self.lock = threading.Lock()
        self.codes = {}           # QR 配對碼 → 到期時間
        self.fails = {}           # ip → [次數, 封到何時]
        self.global_fails = []    # 失敗時間
        self.cfg.setdefault("devices", [])
        self._last_save = time.time()
        if not self.cfg.get("admin_migrated"):   # 加管理者功能前配對的都是主人自己的裝置
            for d in self.cfg["devices"]:
                d.setdefault("admin", True)
            self.cfg["admin_migrated"] = True
            self.save()

    # ---------- 存取碼檢查（每個請求） ----------

    def check(self, auth_header):
        """回傳 (是否通過, 裝置 id 或 "main")。固定時間比對。"""
        given = auth_header[7:] if auth_header.startswith("Bearer ") else ""
        if not given:
            return False, None
        ok_main = hmac.compare_digest(given.encode("utf-8"), self.cfg["token"].encode("utf-8"))
        h = _hash_token(given)
        found = None
        for d in self.cfg["devices"]:
            if hmac.compare_digest(h, d["hash"]):
                found = d
        if found:
            now = time.time()
            found["last_seen"] = int(now)
            if now - self._last_save > SEEN_SAVE_EVERY:
                self._last_save = now
                self.save()
            return True, found["id"]
        return (True, "main") if ok_main else (False, None)

    # ---------- 密碼 ----------

    @property
    def has_password(self):
        return bool(self.cfg.get("password"))

    def set_password(self, password):
        if len(password) < MIN_PASSWORD:
            raise ValueError(f"密碼至少 {MIN_PASSWORD} 個字")
        salt = secrets.token_hex(16)
        self.cfg["password"] = {"salt": salt, "hash": _hash_password(password, salt)}
        self.save()
        self.log("pairing password changed")

    def _password_ok(self, password):
        p = self.cfg.get("password")
        if not p or not password:
            return False
        return hmac.compare_digest(_hash_password(password, p["salt"]), p["hash"])

    # ---------- QR 配對碼 ----------

    def new_code(self):
        code = secrets.token_urlsafe(9)
        now = time.time()
        with self.lock:
            self.codes = {c: t for c, t in self.codes.items() if t > now}
            self.codes[code] = now + CODE_SECONDS
        return code

    def _use_code(self, code):
        now = time.time()
        with self.lock:
            exp = self.codes.pop(code, None) if code else None
        return bool(exp and exp > now)

    # ---------- 配對 ----------

    def blocked(self, ip):
        now = time.time()
        with self.lock:
            self.global_fails = [t for t in self.global_fails if t > now - PAIR_BLOCK]
            if len(self.global_fails) >= GLOBAL_MAX_FAILS:
                return "配對失敗太多次，暫停一小時"
            rec = self.fails.get(ip)
            if rec and rec[1] > now:
                return "密碼錯太多次，請一小時後再試"
        return None

    def pair(self, ip, password=None, code=None, name=""):
        """成功回傳 (存取碼, 裝置 id)；失敗丟 PermissionError（訊息給使用者看）。"""
        why = self.blocked(ip)
        if why:
            raise PermissionError(why)
        if code:
            ok = self._use_code(code)
            wrong = "QR 碼過期或已經用過，請重新整理配對頁再掃一次"
        else:
            if not self.has_password:
                raise PermissionError("主機還沒設定配對密碼：請用已連上的裝置到設定裡設定，或用 QR 碼配對")
            ok = self._password_ok(password or "")
            wrong = "密碼不對"
        if not ok:
            now = time.time()
            with self.lock:
                self.global_fails.append(now)
                rec = self.fails.setdefault(ip, [0, 0])
                rec[0] += 1
                if rec[0] >= PAIR_MAX_FAILS:
                    rec[1] = now + PAIR_BLOCK
                    self.log(f"pairing blocked {ip} after {rec[0]} failures")
            raise PermissionError(wrong)
        with self.lock:
            self.fails.pop(ip, None)
        token = secrets.token_urlsafe(32)
        first = not any(d.get("admin") for d in self.cfg["devices"])   # 還沒有管理者 → 這台就是
        dev = {"id": secrets.token_hex(4), "name": (name or "裝置").strip()[:60], "hash": _hash_token(token),
               "created": int(time.time()), "last_seen": int(time.time()), "via": "qr" if code else "password",
               "admin": first}
        self.cfg["devices"].append(dev)
        self.save()
        self.log(f"paired device {dev['id']} ({dev['name']}) from {ip} via {dev['via']}")
        return token, dev["id"]

    # ---------- 裝置清單 ----------

    def is_admin(self, dev_id):
        """主存取碼（"main"）或標成管理者的裝置。"""
        if dev_id == "main":
            return True
        return any(d["id"] == dev_id and d.get("admin") for d in self.cfg["devices"])

    def devices(self, current):
        """管理者看全部；一般裝置只看得到自己（不讓別人看到其他人的裝置名字）。"""
        admin = self.is_admin(current)
        return [{"id": d["id"], "name": d["name"], "created": d["created"], "last_seen": d.get("last_seen"),
                 "via": d.get("via"), "current": d["id"] == current, "admin": bool(d.get("admin"))}
                for d in self.cfg["devices"] if admin or d["id"] == current]

    def set_admin(self, dev_id, admin):
        """指定 / 取消管理者。至少要留一台管理者裝置（主存取碼不算，一般人手上沒有）。找不到 → False。"""
        d = next((x for x in self.cfg["devices"] if x["id"] == dev_id), None)
        if not d:
            return False
        if not admin and d.get("admin") and sum(1 for x in self.cfg["devices"] if x.get("admin")) <= 1:
            raise ValueError("至少要留一台管理者裝置")
        d["admin"] = bool(admin)
        self.save()
        self.log(f"device {dev_id} admin={d['admin']}")
        return True

    def revoke(self, dev_id):
        others = [d for d in self.cfg["devices"] if d["id"] != dev_id]
        if others and not any(d.get("admin") for d in others) and self.is_admin(dev_id):
            # 取消最後一台管理者：交給最早配對的那台，主機不會變成沒人能管
            min(others, key=lambda d: d["created"])["admin"] = True
        before = len(self.cfg["devices"])
        self.cfg["devices"] = [d for d in self.cfg["devices"] if d["id"] != dev_id]
        if len(self.cfg["devices"]) == before:
            return False
        self.save()
        self.log(f"revoked device {dev_id}")
        return True

    # ---------- QR 配對頁 ----------

    def page(self, url, lan_url):
        """GET /pair（只給區網 / 本機看）：一次性配對碼的 QR。"""
        import qrcode
        import qrcode.image.svg

        code = self.new_code()
        link = "dylan://pair?" + urllib.parse.urlencode({"url": url, "lan": lan_url, "code": code})
        img = qrcode.make(link, image_factory=qrcode.image.svg.SvgPathImage, box_size=12, border=2)
        buf = io.BytesIO()
        img.save(buf)
        svg = buf.getvalue().decode("utf-8")
        svg = svg[svg.index("<svg"):]
        return f"""<!doctype html><html lang="zh-Hant"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>大D的主機：配對</title>
<style>body{{font-family:-apple-system,"PingFang TC",sans-serif;background:#0b1020;color:#e8ecf5;margin:0;
display:flex;min-height:100vh;align-items:center;justify-content:center}}
.c{{max-width:460px;padding:28px;text-align:center}}.q{{background:#fff;border-radius:18px;padding:14px;display:inline-block}}
.q svg{{width:300px;height:300px}}h1{{font-size:24px;margin:0 0 6px}}p{{color:#9aa3b8;line-height:1.6;margin:8px 0}}
code{{color:#e8ecf5}}</style></head><body><div class="c">
<h1>大D的主機：配對新裝置</h1>
<p>用 iPhone / iPad 的<b>相機</b>掃這個 QR 碼，點「在大D中打開」；或在大D App「設定 → 大D的主機 → 掃描 QR 碼」。</p>
<div class="q">{svg}</div>
<p><a href="{html.escape(link)}" style="color:#5fd4ff;font-weight:600">在這台 iPhone / iPad 上直接配對</a>（用這台的 Safari 打開這頁時點這裡）</p>
<p>這個 QR 碼 10 分鐘內有效、只能用一次。重新整理會換一個新的。</p>
<p>連線位址：<code>{url}</code></p></div></body></html>"""
