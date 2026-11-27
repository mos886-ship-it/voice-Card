"""遠距對話（兩地即時翻譯）：大D的主機當中繼站，只轉送文字。

流程：
- 配對過的裝置開一間房（POST /v1/talk）→ 拿到房號 room、邀請碼 code、自己的房間存取碼 token。
- 對方用邀請連結 dylan://talk?url=…&lan=…&room=…&code=… 加入（POST /v1/talk/join）→ 拿到只能用在這間房的 token。
  對方不用跟主機配對、不能用主機的其他功能（報告、模型…）。
- 每支手機只送「自己說的話」的原文＋語言（final=false 是還在說的暫時句，用同一個 line 覆蓋）；
  收到的一方在自己手機上翻成自己的語言（主機不翻譯、不存檔）。
- GET /v1/talk/<room>?after=N&wait=25：等新訊息（長輪詢），回 msgs、members、ended。

安全與限制（主機對外開放）：
- 房號 12 字、邀請碼 8 字、token 32 字，都是 secrets 隨機產生；邀請碼錯太多次（同房 10 次）就作廢。
- 每間房最多 6 人、主機最多 30 間房；一句最多 1000 字；每人每秒最多 8 則；只留最近 300 則。
- 房間 3 小時沒人說話自動關閉；開房的人離開 = 關房。訊息只在記憶體，不寫硬碟、不寫紀錄檔。
"""
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
CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"   # 不用 0 O 1 I L，口頭念也不會搞混


def _rand(n, chars=string.ascii_letters + string.digits):
    return "".join(secrets.choice(chars) for _ in range(n))


class TalkError(Exception):
    def __init__(self, code, msg):
        super().__init__(msg)
        self.code = code


class Rooms:
    def __init__(self, clock=time.time):
        self.clock = clock
        self.rooms = {}
        self.cond = threading.Condition()

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
        return [{"id": mid, "name": m["name"], "lang": m["lang"], "owner": m["owner"]} for mid, m in room["members"].items()]

    def create(self, dev, name, lang):
        with self.cond:
            self._sweep()
            if len(self.rooms) >= MAX_ROOMS:
                raise TalkError(503, "主機上的對話房間太多，請稍後再試")
            rid = _rand(12)
            code = _rand(8, CODE_CHARS)
            room = {"id": rid, "code": code, "dev": dev, "members": {}, "msgs": [], "seq": 0,
                    "last": self.clock(), "ended": False, "fails": 0}
            self.rooms[rid] = room
            mid, token = self._add(room, name, lang, owner=True)
            return {"room": rid, "code": code, "member": mid, "token": token, "members": self._public_members(room)}

    def _add(self, room, name, lang, owner=False):
        if len(room["members"]) >= MAX_MEMBERS:
            raise TalkError(403, "這個對話人數已滿")
        mid = _rand(6)
        token = _rand(32)
        room["members"][mid] = {"name": (str(name or "")[:30] or "?"), "lang": str(lang or "")[:20], "token": token,
                                "owner": owner, "sent": []}
        self._system(room, "join", mid)
        return mid, token

    def join(self, rid, code, name, lang):
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
            mid, token = self._add(room, name, lang)
            self.cond.notify_all()
            return {"room": rid, "member": mid, "token": token, "members": self._public_members(room)}

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
    def post(self, rid, token, line, text, lang, final):
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
            room["msgs"].append({"seq": room["seq"], "from": mid, "name": room["members"][mid]["name"], "line": line,
                                 "text": text, "lang": str(lang or room["members"][mid]["lang"])[:20], "final": bool(final), "at": now})
            del room["msgs"][:-MAX_MSGS]
            room["last"] = now
            self.cond.notify_all()
            return {"seq": room["seq"]}

    def poll(self, rid, token, after, wait):
        deadline = self.clock() + max(0.0, min(float(wait or 0), 25.0))
        with self.cond:
            while True:
                room, mid = self._room_for(rid, token)
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
            self.cond.notify_all()
            return {"ended": room["ended"]}
