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
            status = {}
            for w in (watch or [])[:200]:
                w = str(w)
                if w in peers and w in self.contacts:
                    t = self.seen.get(w)
                    status[w] = {"online": bool(t and now - t <= ONLINE_SECONDS), "seen": t,
                                 "name": self.contacts[w].get("name"), "lang": self.contacts[w].get("lang")}
            rings = [r for r in self.rings.get(cid, []) if now - r["at"] <= 24 * 3600]
            self.rings[cid] = rings[-20:]
            calls = []
            for r in rings:
                room = self.rooms.get(r["room"])
                live = bool(room and not room["ended"] and room["code"] and now - r["at"] <= RING_SECONDS)
                calls.append({**r, "live": live})
            return {"me": cid, "peers": status, "calls": calls}

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
