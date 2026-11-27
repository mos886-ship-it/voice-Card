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


if __name__ == "__main__":
    unittest.main()
