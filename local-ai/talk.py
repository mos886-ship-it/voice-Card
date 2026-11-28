"""遠距對話（兩地即時翻譯）：大D的主機當中繼站，轉送文字與每句的原聲片段。

流程：
- 配對過的裝置開一間房（POST /v1/talk）→ 拿到房號 room、邀請碼 code、自己的房間存取碼 token。
- 對方用邀請連結 dylan://talk?url=…&lan=…&room=…&code=… 加入（POST /v1/talk/join）→ 拿到只能用在這間房的 token。
  對方不用跟主機配對、不能用主機的其他功能（報告、模型…）。
- 每支手機只送「自己說的話」的原文＋語言（final=false 是還在說的暫時句，用同一個 line 覆蓋）；
  說完的那句可以先傳原聲片段（POST /v1/talk/<room>/audio → aid），再在 final 那則帶 audio=aid。
  收到的一方在自己手機上翻成自己的語言、播原聲再唸翻譯（主機不翻譯）。
- GET /v1/talk/<room>?after=N&wait=25：等新訊息（長輪詢），回 msgs、members、ended。

通話對象（聯絡人）：
- 開房、加入時會拿到一個長期的「聯絡人身分」contact {id, token}（token 只回一次，主機只存雜湊）。之後帶 contact token 開房 / 加入，就是同一個人。
- 同一間房待過的人互相成為「通話過的對象」（peers），存在 talk_contacts.json（只有名字、語言、雜湊、對象清單；沒有對話內容）。
- POST /v1/talk/presence（X-Contact-Token）{watch:[id…]}：我上線了（App 開著時大約每 20 秒一次），回這些對象誰在線上、誰打給我。
  「在線上」＝ 90 秒內有回報。主機沒辦法叫醒關著的 App（那要 Google / Apple 的推播），所以對方沒開 App 就是「未接」。
- POST /v1/talk/call（X-Contact-Token）{to, name, lang}：直接打給通話過的對象：開一間房、在對方那邊留來電（2 分鐘內接都可以）。

使用者 ID（加好友）：
- POST /v1/talk/register：取得自己的使用者 ID（8 碼，像 K7MX-29QP，好念好打）。配對過的裝置（Authorization）或已經有通話身分（X-Contact-Token）的人才能註冊。
- POST /v1/talk/friend（X-Contact-Token）{id: "K7MX-29QP"}：輸入對方的 ID → 對方收到好友邀請；對方接受後互相成為好友（＝可以互打）。
  對方也加過我 → 直接成為好友。
- POST /v1/talk/friend/answer {from, accept}：接受 / 拒絕好友邀請。POST /v1/talk/unfriend {id}：刪除好友（雙方都拿掉）。
- 上線回報多回：自己的 uid、收到的好友邀請 requests、我送出還沒被接受的 pending。

頭貼：
- POST /v1/talk/avatar（X-Contact-Token，body = JPEG，最多 64 KB）換自己的頭貼；body 空的 = 刪掉。存在 talk_avatars/<id>.jpg（不放進 talk_contacts.json）。
- GET /v1/talk/avatar/<id>（X-Contact-Token）：只有自己、好友、互相有邀請的人拿得到。
- 上線回報、好友邀請、來電都帶 av（頭貼版本 = 換的時間；0 = 沒有），App 看版本變了才重抓。

安全與限制（主機對外開放）：
- 房號 12 字、邀請碼 8 字、token 32 字，都是 secrets 隨機產生；邀請碼錯太多次（同房 10 次）就作廢。
- 只能打給「同一間房待過」的人；每人 10 秒最多打一通。
- 每間房最多 6 人、主機最多 30 間房；一句最多 1000 字；每人每秒最多 8 則；只留最近 300 則。
- 原聲片段一段最多 400 KB、每間房最多 30 MB（超過丟最舊的）；只在記憶體。
- 房間 3 小時沒人說話自動關閉；開房的人離開 = 關房。對話內容只在記憶體，不寫硬碟、不寫紀錄檔。
"""
import hashlib
import json
import os
import secrets
import string
import threading
import time

MAX_ROOMS = 30
MAX_MEMBERS = 6
MAX_TEXT = 1000
MAX_MSGS = 300
IDLE_SECONDS = 3 * 3600
RATE_PER_SEC = 8
MAX_JOIN_FAILS = 10
MAX_AUDIO = 400_000
MAX_ROOM_AUDIO = 30_000_000
ONLINE_SECONDS = 90
RING_SECONDS = 120
CALL_GAP = 10
MAX_CONTACTS = 2000
CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"   # 不用 0 O 1 I L，口頭念也不會搞混
UID_LEN = 8
FRIEND_GAP = 2          # 加好友：每人 2 秒最多一次
MAX_REQUESTS = 50       # 每人最多掛 50 個沒處理的好友邀請
MAX_AVATAR = 64_000     # 頭貼一張最多 64 KB（App 縮成 256×256 JPEG，大約 15 KB）


def norm_uid(s):
    """使用者輸入的 ID → 標準寫法（大寫、去掉 - 和空白；容易混的 0→O 不用處理，因為 ID 裡根本沒有）。"""
    return "".join(ch for ch in str(s or "").upper() if ch in CODE_CHARS)[:UID_LEN]


def show_uid(uid):
    return f"{uid[:4]}-{uid[4:]}" if uid and len(uid) == UID_LEN else (uid or "")


def _rand(n, chars=string.ascii_letters + string.digits):
    return "".join(secrets.choice(chars) for _ in range(n))


def _hash(token):
    return hashlib.sha256(str(token).encode("utf-8")).hexdigest()


class TalkError(Exception):
    def __init__(self, code, msg):
        super().__init__(msg)
        self.code = code


class Rooms:
    def __init__(self, clock=time.time, contacts_path=None):
        self.clock = clock
        self.rooms = {}
        self.cond = threading.Condition()
        self.path = contacts_path
        self.contacts = {}      # id → {name, lang, hash, dev, peers:[id]}
        self.by_hash = {}       # 雜湊 → id
        self.by_dev = {}        # 配對裝置 → id
        self.by_uid = {}        # 使用者 ID → id
        self.mem_avatars = {}   # 沒有存檔路徑（測試）時頭貼放記憶體
        self.last_friend = {}
        self.seen = {}          # id → 最後回報時間（不存檔）
        self.rings = {}         # id → [{room, code, from, name, lang, at}]（不存檔）
        self.last_call = {}
        self._load()

    # ---------- 聯絡人（存檔：只有名字、語言、雜湊、對象） ----------
    def _load(self):
        if not self.path or not os.path.exists(self.path):
            return
        try:
            with open(self.path, encoding="utf-8") as f:
                self.contacts = json.load(f).get("contacts", {})
        except (OSError, ValueError):
            self.contacts = {}
        for cid, c in self.contacts.items():
            self.by_hash[c["hash"]] = cid
            if c.get("dev"):
                self.by_dev[c["dev"]] = cid
            if c.get("uid"):
                self.by_uid[c["uid"]] = cid

    def _save(self):
        if not self.path:
            return
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"contacts": self.contacts}, f, ensure_ascii=False)
        try:
            os.chmod(tmp, 0o600)
        except OSError:
            pass
        os.replace(tmp, self.path)

    def _contact(self, token):
        return self.by_hash.get(_hash(token)) if token else None

    def has_contact(self, token):
        with self.cond:
            return bool(self._contact(token))

    def _ensure_contact(self, token, name, lang, dev=None):
        """帶 token → 同一個人（更新名字、語言）；沒有 → 新的（配對裝置沿用它自己的身分，換一把新 token）。
        回 (id, 新 token 或 None)。"""
        cid = self._contact(token)
        new_token = None
        if not cid and dev and dev in self.by_dev:
            cid = self.by_dev[dev]
            new_token = _rand(32)
            self.by_hash.pop(self.contacts[cid]["hash"], None)
            self.contacts[cid]["hash"] = _hash(new_token)
            self.by_hash[_hash(new_token)] = cid
        if not cid:
            if len(self.contacts) >= MAX_CONTACTS:
                raise TalkError(503, "主機上的通話對象太多了")
            cid = _rand(10)
            new_token = _rand(32)
            self.contacts[cid] = {"name": "", "lang": "", "hash": _hash(new_token), "dev": dev, "peers": []}
            self.by_hash[_hash(new_token)] = cid
            if dev:
                self.by_dev[dev] = cid
        c = self.contacts[cid]
        c["name"] = (str(name or "")[:30] or c.get("name") or "?")
        c["lang"] = str(lang or c.get("lang") or "")[:20]
        self.seen[cid] = self.clock()
        self._save()
        return cid, new_token

    def _link(self, room, cid):
        """同一間房的人互相成為通話過的對象。"""
        changed = False
        for m in room["members"].values():
            other = m.get("contact")
            if other and other != cid and other in self.contacts:
                for a, b in ((cid, other), (other, cid)):
                    if b not in self.contacts[a]["peers"]:
                        self.contacts[a]["peers"].append(b)
                        changed = True
        if changed:
            self._save()

    def _uid(self, cid):
        """這個人的使用者 ID（第一次用到才產生）。"""
        c = self.contacts[cid]
        if not c.get("uid"):
            while True:
                uid = _rand(UID_LEN, CODE_CHARS)
                if uid not in self.by_uid:
                    break
            c["uid"] = uid
            self.by_uid[uid] = cid
            self._save()
        return c["uid"]

    def _brief(self, cid):
        c = self.contacts[cid]
        return {"id": cid, "name": c.get("name"), "lang": c.get("lang"), "uid": show_uid(c.get("uid")), "av": c.get("av", 0)}

    # ---------- 頭貼 ----------
    def _avatar_path(self, cid):
        if not self.path:
            return None
        d = os.path.join(os.path.dirname(os.path.abspath(self.path)), "talk_avatars")
        os.makedirs(d, exist_ok=True)
        return os.path.join(d, cid + ".jpg")

    def set_avatar(self, contact_token, data):
        """換自己的頭貼（JPEG）；data 空的 = 刪掉。回新的版本。"""
        with self.cond:
            cid = self._contact(contact_token)
            if not cid:
                raise TalkError(401, "不認得這個通話身分")
            if data and len(data) > MAX_AVATAR:
                raise TalkError(413, "頭貼太大")
            if data and not data.startswith(b"\xff\xd8"):
                raise TalkError(400, "頭貼要是 JPEG")
            path = self._avatar_path(cid)
            c = self.contacts[cid]
            if data:
                if path:
                    tmp = path + ".tmp"
                    with open(tmp, "wb") as f:
                        f.write(data)
                    os.replace(tmp, path)
                else:
                    self.mem_avatars[cid] = bytes(data)
                c["av"] = int(self.clock() * 1000)
            else:
                if path and os.path.exists(path):
                    os.remove(path)
                self.mem_avatars.pop(cid, None)
                c["av"] = 0
            self._save()
            return {"av": c["av"]}

    def get_avatar(self, contact_token, cid):
        """拿頭貼：自己、好友、互相有邀請的人才可以。"""
        with self.cond:
            me = self._contact(contact_token)
            if not me:
                raise TalkError(401, "不認得這個通話身分")
            cid = str(cid)
            c = self.contacts.get(me, {})
            allowed = cid == me or cid in c.get("peers", []) or cid in c.get("req_in", []) or cid in c.get("req_out", [])
            if not allowed or cid not in self.contacts or not self.contacts[cid].get("av"):
                raise TalkError(404, "沒有頭貼")
            path = self._avatar_path(cid)
            if path:
                try:
                    with open(path, "rb") as f:
                        return f.read()
                except OSError:
                    raise TalkError(404, "沒有頭貼")
            data = self.mem_avatars.get(cid)
            if not data:
                raise TalkError(404, "沒有頭貼")
            return data

    def _befriend(self, a, b):
        for x, y in ((a, b), (b, a)):
            cx = self.contacts[x]
            if y not in cx["peers"]:
                cx["peers"].append(y)
            for k in ("req_in", "req_out"):
                if y in cx.get(k, []):
                    cx[k].remove(y)

    def register(self, contact_token, name, lang, dev=None):
        """取得使用者 ID。有通話身分就用它；沒有的話要是配對過的裝置（[dev]）才給新的。"""
        with self.cond:
            if not self._contact(contact_token) and not dev:
                raise TalkError(401, "要先跟大D的主機配對，或先用邀請連結加入過一次對話")
            cid, tok = self._ensure_contact(contact_token, name, lang, dev=dev)
            return {"contact": self._contact_out(cid, tok), "uid": show_uid(self._uid(cid))}

    def add_friend(self, contact_token, uid):
        with self.cond:
            cid = self._contact(contact_token)
            if not cid:
                raise TalkError(401, "不認得這個通話身分")
            now = self.clock()
            if now - self.last_friend.get(cid, 0) < FRIEND_GAP:
                raise TalkError(429, "太快了，等一下再試")
            self.last_friend[cid] = now
            u = norm_uid(uid)
            if len(u) != UID_LEN:
                raise TalkError(400, "ID 是 8 個英文字母或數字，例如 K7MX-29QP")
            to = self.by_uid.get(u)
            if not to or to not in self.contacts:
                raise TalkError(404, "找不到這個 ID")
            if to == cid:
                raise TalkError(400, "這是你自己的 ID")
            me, them = self.contacts[cid], self.contacts[to]
            if to in me["peers"]:
                status = "friends"
            elif to in me.get("req_in", []):          # 對方也加過我 → 直接成為好友
                self._befriend(cid, to)
                status = "friends"
            else:
                inbox = them.setdefault("req_in", [])
                if cid not in inbox:
                    if len(inbox) >= MAX_REQUESTS:
                        raise TalkError(429, "對方的好友邀請太多了，請稍後再試")
                    inbox.append(cid)
                out = me.setdefault("req_out", [])
                if to not in out:
                    out.append(to)
                status = "pending"
            self._save()
            return {"status": status, "friend": self._brief(to)}

    def answer_friend(self, contact_token, frm, accept):
        with self.cond:
            cid = self._contact(contact_token)
            if not cid:
                raise TalkError(401, "不認得這個通話身分")
            frm = str(frm)
            me = self.contacts[cid]
            if frm not in me.get("req_in", []) or frm not in self.contacts:
                raise TalkError(404, "沒有這個好友邀請")
            if accept:
                self._befriend(cid, frm)
            else:
                me["req_in"].remove(frm)
                out = self.contacts[frm].get("req_out", [])
                if cid in out:
                    out.remove(cid)
            self._save()
            return {"ok": True, "friend": self._brief(frm) if accept else None}

    def unfriend(self, contact_token, other):
        with self.cond:
            cid = self._contact(contact_token)
            if not cid:
                raise TalkError(401, "不認得這個通話身分")
            other = str(other)
            for x, y in ((cid, other), (other, cid)):
                cx = self.contacts.get(x)
                if not cx:
                    continue
                for k in ("peers", "req_in", "req_out"):
                    if y in cx.get(k, []):
                        cx[k].remove(y)
            self._save()
            return {"ok": True}

    def _contact_out(self, cid, token):
        return {"id": cid, "token": token} if token else {"id": cid}

    # ---------- 房間 ----------
    def _sweep(self):
        now = self.clock()
        for rid in [r for r, v in self.rooms.items() if now - v["last"] > IDLE_SECONDS or (v["ended"] and now - v["last"] > 60)]:
            self.rooms.pop(rid, None)

    def _member(self, room, token):
        if not token:
            return None
        for mid, m in room["members"].items():
            if secrets.compare_digest(m["token"], token):
                return mid
        return None

    def _public_members(self, room):
        return [{"id": mid, "name": m["name"], "lang": m["lang"], "owner": m["owner"], "contact": m.get("contact")}
                for mid, m in room["members"].items()]

    def _new_room(self, dev):
        if len(self.rooms) >= MAX_ROOMS:
            raise TalkError(503, "主機上的對話房間太多，請稍後再試")
        rid = _rand(12)
        room = {"id": rid, "code": _rand(8, CODE_CHARS), "dev": dev, "members": {}, "msgs": [], "seq": 0,
                "last": self.clock(), "ended": False, "fails": 0, "audio": {}, "audio_order": [], "audio_bytes": 0}
        self.rooms[rid] = room
        return room

    def create(self, dev, name, lang, contact=None):
        with self.cond:
            self._sweep()
            cid, ctok = self._ensure_contact(contact, name, lang, dev=dev)
            room = self._new_room(dev)
            mid, token = self._add(room, name, lang, owner=True, contact=cid)
            return {"room": room["id"], "code": room["code"], "member": mid, "token": token,
                    "members": self._public_members(room), "contact": self._contact_out(cid, ctok)}

    def _add(self, room, name, lang, owner=False, contact=None):
        if len(room["members"]) >= MAX_MEMBERS:
            raise TalkError(403, "這個對話人數已滿")
        mid = _rand(6)
        token = _rand(32)
        room["members"][mid] = {"name": (str(name or "")[:30] or "?"), "lang": str(lang or "")[:20], "token": token,
                                "owner": owner, "sent": [], "contact": contact}
        if contact:
            self._link(room, contact)
        self._system(room, "join", mid)
        return mid, token

    def join(self, rid, code, name, lang, contact=None):
        with self.cond:
            self._sweep()
            room = self.rooms.get(str(rid))
            if not room or room["ended"] or room["code"] is None:
                raise TalkError(404, "找不到這個對話，或已經結束")
            if not secrets.compare_digest(room["code"], str(code or "").strip().upper()):
                room["fails"] += 1
                if room["fails"] >= MAX_JOIN_FAILS:
                    room["code"] = None   # 一直猜錯：邀請碼作廢（開房的人可以重開一間）
                raise TalkError(403, "邀請碼不對")
            cid, ctok = self._ensure_contact(contact, name, lang)
            mid, token = self._add(room, name, lang, contact=cid)
            # 接了來電：把這通的來電拿掉
            self.rings[cid] = [r for r in self.rings.get(cid, []) if r["room"] != room["id"]]
            self.cond.notify_all()
            return {"room": rid, "member": mid, "token": token, "members": self._public_members(room),
                    "contact": self._contact_out(cid, ctok)}

    def _system(self, room, kind, mid):
        room["seq"] += 1
        room["msgs"].append({"seq": room["seq"], "sys": kind, "from": mid,
                             "name": room["members"].get(mid, {}).get("name", ""), "at": self.clock()})
        del room["msgs"][:-MAX_MSGS]
        room["last"] = self.clock()

    def _room_for(self, rid, token):
        room = self.rooms.get(str(rid))
        if not room:
            raise TalkError(404, "找不到這個對話，或已經結束")
        mid = self._member(room, token)
        if not mid:
            raise TalkError(401, "不是這個對話的成員")
        return room, mid

    # ---------- 訊息 ----------
    def post(self, rid, token, line, text, lang, final, audio=None):
        with self.cond:
            room, mid = self._room_for(rid, token)
            if room["ended"]:
                raise TalkError(410, "對話已經結束")
            text = str(text or "")[:MAX_TEXT]
            now = self.clock()
            sent = [t for t in room["members"][mid]["sent"] if now - t < 1.0]
            if len(sent) >= RATE_PER_SEC:
                raise TalkError(429, "送太快了")
            sent.append(now)
            room["members"][mid]["sent"] = sent
            line = str(line or "")[:40]
            # 同一句的暫時版本：舊的拿掉，只留最新（省流量；最後 final 的那則一定會留著）
            room["msgs"] = [m for m in room["msgs"] if not (m.get("line") == line and m.get("from") == mid and not m.get("final"))]
            room["seq"] += 1
            msg = {"seq": room["seq"], "from": mid, "name": room["members"][mid]["name"], "line": line,
                   "text": text, "lang": str(lang or room["members"][mid]["lang"])[:20], "final": bool(final), "at": now}
            if audio and str(audio) in room["audio"]:
                msg["audio"] = str(audio)
            room["msgs"].append(msg)
            del room["msgs"][:-MAX_MSGS]
            room["last"] = now
            self.cond.notify_all()
            return {"seq": room["seq"]}

    def put_audio(self, rid, token, data, mime="audio/mp4"):
        with self.cond:
            room, mid = self._room_for(rid, token)
            if room["ended"]:
                raise TalkError(410, "對話已經結束")
            if not data or len(data) > MAX_AUDIO:
                raise TalkError(413, "原聲片段太大")
            aid = _rand(10)
            room["audio"][aid] = {"data": bytes(data), "mime": str(mime)[:40], "from": mid}
            room["audio_order"].append(aid)
            room["audio_bytes"] += len(data)
            while room["audio_bytes"] > MAX_ROOM_AUDIO and room["audio_order"]:
                old = room["audio"].pop(room["audio_order"].pop(0), None)
                if old:
                    room["audio_bytes"] -= len(old["data"])
            room["last"] = self.clock()
            return {"audio": aid}

    def get_audio(self, rid, token, aid):
        with self.cond:
            room, _ = self._room_for(rid, token)
            a = room["audio"].get(str(aid))
            if not a:
                raise TalkError(404, "原聲片段已經不在了")
            return a["data"], a["mime"]

    def poll(self, rid, token, after, wait):
        deadline = self.clock() + max(0.0, min(float(wait or 0), 25.0))
        with self.cond:
            while True:
                room, mid = self._room_for(rid, token)
                cid = room["members"][mid].get("contact")
                if cid:
                    self.seen[cid] = self.clock()   # 在對話裡也算上線
                msgs = [m for m in room["msgs"] if m["seq"] > after]
                if msgs or room["ended"] or self.clock() >= deadline:
                    return {"msgs": msgs, "members": self._public_members(room), "ended": room["ended"],
                            "me": mid, "seq": room["seq"]}
                self.cond.wait(timeout=max(0.05, deadline - self.clock()))

    def leave(self, rid, token):
        with self.cond:
            room, mid = self._room_for(rid, token)
            owner = room["members"][mid]["owner"]
            self._system(room, "leave", mid)
            room["members"].pop(mid, None)
            if owner or not room["members"]:
                room["ended"] = True
                room["code"] = None
                room["audio"].clear()
                room["audio_order"].clear()
                room["audio_bytes"] = 0
            self.cond.notify_all()
            return {"ended": room["ended"]}

    # ---------- 上線、來電 ----------
    def presence(self, contact_token, watch, name=None, lang=None):
        with self.cond:
            self._sweep()
            cid = self._contact(contact_token)
            if not cid:
                raise TalkError(401, "不認得這個通話身分")
            now = self.clock()
            self.seen[cid] = now
            c = self.contacts[cid]
            if name:
                c["name"] = str(name)[:30]
            if lang:
                c["lang"] = str(lang)[:20]
            peers = set(c["peers"])
            out = set(c.get("req_out", []))
            status = {}
            for w in (watch or [])[:200]:
                w = str(w)
                if w in peers and w in self.contacts:
                    t = self.seen.get(w)
                    status[w] = {"online": bool(t and now - t <= ONLINE_SECONDS), "seen": t,
                                 "name": self.contacts[w].get("name"), "lang": self.contacts[w].get("lang"),
                                 "uid": show_uid(self.contacts[w].get("uid")), "av": self.contacts[w].get("av", 0)}
                elif w in out and w in self.contacts:   # 我加了、對方還沒接受
                    status[w] = {"online": False, "pending": True, **{k: v for k, v in self._brief(w).items() if k != "id"}}
            requests = [self._brief(r) for r in c.get("req_in", []) if r in self.contacts]
            rings = [r for r in self.rings.get(cid, []) if now - r["at"] <= 24 * 3600]
            self.rings[cid] = rings[-20:]
            calls = []
            for r in rings:
                room = self.rooms.get(r["room"])
                live = bool(room and not room["ended"] and room["code"] and now - r["at"] <= RING_SECONDS)
                calls.append({**r, "live": live, "av": self.contacts.get(r.get("from"), {}).get("av", 0)})
            return {"me": cid, "uid": show_uid(self._uid(cid)), "av": c.get("av", 0), "peers": status, "calls": calls, "requests": requests}

    def dismiss(self, contact_token, room_id):
        """看過的未接來電拿掉。"""
        with self.cond:
            cid = self._contact(contact_token)
            if not cid:
                raise TalkError(401, "不認得這個通話身分")
            self.rings[cid] = [r for r in self.rings.get(cid, []) if r["room"] != str(room_id)]
            return {"ok": True}

    def call(self, contact_token, to, name, lang):
        with self.cond:
            self._sweep()
            cid = self._contact(contact_token)
            if not cid:
                raise TalkError(401, "不認得這個通話身分")
            to = str(to)
            if to not in self.contacts.get(cid, {}).get("peers", []):
                raise TalkError(403, "只能打給通話過的對象")
            now = self.clock()
            if now - self.last_call.get(cid, 0) < CALL_GAP:
                raise TalkError(429, "打太快了，等幾秒再試")
            self.last_call[cid] = now
            c = self.contacts[cid]
            if name:
                c["name"] = str(name)[:30]
            if lang:
                c["lang"] = str(lang)[:20]
            self.seen[cid] = now
            room = self._new_room(c.get("dev"))
            mid, token = self._add(room, c["name"], c["lang"], owner=True, contact=cid)
            self.rings.setdefault(to, []).append({"room": room["id"], "code": room["code"], "from": cid,
                                                   "name": c["name"], "lang": c["lang"], "at": now})
            self.cond.notify_all()
            return {"room": room["id"], "code": room["code"], "member": mid, "token": token,
                    "members": self._public_members(room), "contact": {"id": cid}}
