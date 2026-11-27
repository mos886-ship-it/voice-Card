"""遠距對話中繼（talk.py）的測試：python talk_test.py"""
import threading
import time
import unittest

import talk


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


class TalkTest(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.r = talk.Rooms(clock=self.clock)

    def test_create_join_and_exchange(self):
        a = self.r.create("dev1", "大D", "cmn-Hant-TW")
        self.assertEqual(len(a["code"]), 8)
        b = self.r.join(a["room"], a["code"].lower(), "Yuki", "ja-JP")
        self.assertEqual(len(b["members"]), 2)
        self.r.post(a["room"], a["token"], "l1", "你好", "cmn-Hant-TW", False)
        self.r.post(a["room"], a["token"], "l1", "你好嗎", "cmn-Hant-TW", True)
        got = self.r.poll(a["room"], b["token"], 0, 0)
        texts = [m["text"] for m in got["msgs"] if "text" in m]
        self.assertEqual(texts, ["你好嗎"])            # 暫時句被最後的 final 取代
        self.assertTrue(any(m.get("sys") == "join" for m in got["msgs"]))
        self.assertEqual(got["me"], b["member"])

    def test_wrong_code_and_lockout(self):
        a = self.r.create("dev1", "A", "en-US")
        for _ in range(talk.MAX_JOIN_FAILS):
            with self.assertRaises(talk.TalkError):
                self.r.join(a["room"], "WRONG123", "x", "en")
        with self.assertRaises(talk.TalkError):
            self.r.join(a["room"], a["code"], "x", "en")   # 猜錯太多次，邀請碼作廢

    def test_outsider_cannot_read(self):
        a = self.r.create("dev1", "A", "en-US")
        with self.assertRaises(talk.TalkError) as e:
            self.r.poll(a["room"], "nope", 0, 0)
        self.assertEqual(e.exception.code, 401)

    def test_rate_limit_and_length(self):
        a = self.r.create("dev1", "A", "en-US")
        for i in range(talk.RATE_PER_SEC):
            self.r.post(a["room"], a["token"], f"l{i}", "x" * 5000, "en", True)
        with self.assertRaises(talk.TalkError) as e:
            self.r.post(a["room"], a["token"], "lx", "y", "en", True)
        self.assertEqual(e.exception.code, 429)
        got = self.r.poll(a["room"], a["token"], 0, 0)
        self.assertTrue(all(len(m.get("text", "")) <= talk.MAX_TEXT for m in got["msgs"]))

    def test_owner_leaving_ends_room_and_idle_expiry(self):
        a = self.r.create("dev1", "A", "en-US")
        b = self.r.join(a["room"], a["code"], "B", "ko-KR")
        self.assertTrue(self.r.leave(a["room"], a["token"])["ended"])
        self.assertTrue(self.r.poll(a["room"], b["token"], 0, 0)["ended"])
        c = self.r.create("dev1", "C", "en-US")
        self.clock.t += talk.IDLE_SECONDS + 1
        self.r.create("dev1", "D", "en-US")   # 觸發清理
        with self.assertRaises(talk.TalkError):
            self.r.poll(c["room"], c["token"], 0, 0)

    def test_long_poll_wakes_up(self):
        r = talk.Rooms()   # 真的時間
        a = r.create("dev1", "A", "en-US")
        b = r.join(a["room"], a["code"], "B", "ja-JP")
        seq = r.poll(a["room"], b["token"], 0, 0)["seq"]
        out = {}
        t = threading.Thread(target=lambda: out.update(r.poll(a["room"], b["token"], seq, 5)))
        t.start()
        time.sleep(0.2)
        r.post(a["room"], a["token"], "l1", "hello", "en-US", True)
        t.join(3)
        self.assertEqual([m["text"] for m in out["msgs"]], ["hello"])




class ContactsTest(unittest.TestCase):
    def setUp(self):
        import tempfile, os
        self.clock = Clock()
        self.path = os.path.join(tempfile.mkdtemp(), "c.json")
        self.r = talk.Rooms(clock=self.clock, contacts_path=self.path)

    def test_contacts_presence_call_audio(self):
        a = self.r.create("dev1", "大D", "cmn-Hant-TW")
        ca = a["contact"]
        self.assertIn("token", ca)
        b = self.r.join(a["room"], a["code"], "Yuki", "ja-JP")
        cb = b["contact"]
        # 互相成為通話過的對象；在線上
        p = self.r.presence(ca["token"], [cb["id"], "stranger"])
        self.assertEqual(list(p["peers"]), [cb["id"]])
        self.assertTrue(p["peers"][cb["id"]]["online"])
        # 原聲片段
        aid = self.r.put_audio(a["room"], a["token"], b"\x00" * 1000)["audio"]
        self.r.post(a["room"], a["token"], "l1", "你好", "cmn-Hant-TW", True, audio=aid)
        msg = [m for m in self.r.poll(a["room"], b["token"], 0, 0)["msgs"] if m.get("text")][0]
        self.assertEqual(msg["audio"], aid)
        self.assertEqual(len(self.r.get_audio(a["room"], b["token"], aid)[0]), 1000)
        with self.assertRaises(talk.TalkError):
            self.r.put_audio(a["room"], a["token"], b"x" * (talk.MAX_AUDIO + 1))
        # 下次：Yuki 直接打給大D，帶著同一個身分
        self.clock.t += 60
        call = self.r.call(cb["token"], ca["id"], "Yuki", "ja-JP")
        rings = self.r.presence(ca["token"], [cb["id"]])["calls"]
        self.assertEqual(rings[0]["room"], call["room"])
        self.assertTrue(rings[0]["live"])
        j = self.r.join(call["room"], rings[0]["code"], "大D", "cmn-Hant-TW", contact=ca["token"])
        self.assertEqual(j["contact"]["id"], ca["id"])
        self.assertNotIn("token", j["contact"])        # 同一個人，不換 token
        self.assertEqual(self.r.presence(ca["token"], [])["calls"], [])   # 接了就不再顯示
        # 不能打給陌生人；打太快
        with self.assertRaises(talk.TalkError) as e:
            self.r.call(ca["token"], "stranger", "大D", "zh")
        self.assertEqual(e.exception.code, 403)
        with self.assertRaises(talk.TalkError) as e:
            self.r.call(cb["token"], ca["id"], "Yuki", "ja-JP")
        self.assertEqual(e.exception.code, 429)
        # 存檔：重開主機還認得（只有名字、語言、雜湊、對象）
        r2 = talk.Rooms(clock=self.clock, contacts_path=self.path)
        self.assertEqual(r2.presence(cb["token"], [ca["id"]])["me"], cb["id"])
        saved = open(self.path, encoding="utf-8").read()
        self.assertNotIn(cb["token"], saved)
        self.assertNotIn("你好", saved)

    def test_missed_call_and_offline(self):
        a = self.r.create("dev1", "A", "en-US")
        b = self.r.join(a["room"], a["code"], "B", "ko-KR")
        self.r.leave(a["room"], a["token"])
        self.clock.t += talk.ONLINE_SECONDS + 5
        p = self.r.presence(b["contact"]["token"], [a["contact"]["id"]])
        self.assertFalse(p["peers"][a["contact"]["id"]]["online"])
        self.r.call(b["contact"]["token"], a["contact"]["id"], "B", "ko-KR")
        self.clock.t += talk.RING_SECONDS + 5
        calls = self.r.presence(a["contact"]["token"], [])["calls"]
        self.assertFalse(calls[0]["live"])        # 未接
        self.r.dismiss(a["contact"]["token"], calls[0]["room"])
        self.assertEqual(self.r.presence(a["contact"]["token"], [])["calls"], [])

    def test_paired_device_keeps_identity(self):
        a = self.r.create("dev1", "A", "en-US")
        again = self.r.create("dev1", "A", "en-US")          # 沒帶 token（例如重裝 App）
        self.assertEqual(again["contact"]["id"], a["contact"]["id"])
        self.assertIn("token", again["contact"])              # 換新 token
        with self.assertRaises(talk.TalkError):
            self.r.presence(a["contact"]["token"], [])         # 舊 token 失效


if __name__ == "__main__":
    unittest.main()
