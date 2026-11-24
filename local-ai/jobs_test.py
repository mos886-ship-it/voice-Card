"""jobs.py 的測試（不用模型，哪台電腦都能跑）：python jobs_test.py"""
import os
import tempfile
import threading
import time
import unittest

import jobs as J


def write_bytes(data):
    def w(path):
        with open(path, "wb") as f:
            f.write(data)
    return w


class JobsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.ran = []

        def run(j, path):
            with open(path, "rb") as f:
                body = f.read()
            with open(path + ".wav", "wb") as f:   # 跟 server.to_wav 一樣在旁邊產生轉檔
                f.write(body)
            if body == b"boom":
                raise RuntimeError("壞掉了")
            self.ran.append((j["dev"], body.decode()))
            return {"text": "TITLE: " + body.decode(), "model": "local-test|x"}
        self.run = run
        self.q = J.Jobs(self.tmp.name, run, log=lambda *_: None)

    def tearDown(self):
        self.tmp.cleanup()

    def add(self, dev, name, duration=60):
        return self.q.submit(dev, {"size": len(name), "kind": "meeting", "duration": duration}, write_bytes(name.encode()))

    def test_round_robin(self):
        """A 一次丟 3 筆、B 之後丟 1 筆：B 不用等 A 全部做完。"""
        for n in ("a1", "a2", "a3"):
            self.add("A", n)
        b = self.add("B", "b1")
        self.assertEqual(self.q.view(b)["ahead"], 1)          # 前面只有 a1
        while self.q.run_next(block=False):
            pass
        self.assertEqual([x[1] for x in self.ran], ["a1", "b1", "a2", "a3"])

    def test_served_device_waits_for_new_one(self):
        """A 剛做完一筆，A 再送跟 C 第一次送同時排：C 先。"""
        self.add("A", "a1")
        self.q.run_next(block=False)
        self.add("A", "a2")
        c = self.add("C", "c1")
        self.assertEqual(self.q.view(c)["ahead"], 0)
        self.q.run_next(block=False)
        self.assertEqual(self.ran[-1][1], "c1")

    def test_result_and_delete(self):
        j = self.add("A", "hello")
        self.assertEqual(self.q.view(j)["state"], "queued")
        self.q.run_next(block=False)
        v = self.q.view(self.q.get(j["id"], "A"))
        self.assertEqual((v["state"], v["text"], v["model"]), ("done", "TITLE: hello", "local-test|x"))
        self.assertEqual(os.listdir(os.path.join(self.tmp.name, j["id"])), ["job.json"])   # 錄音、轉檔做完就刪
        self.assertIsNone(self.q.get(j["id"], "B"))                    # 別台看不到
        self.assertIsNotNone(self.q.get(j["id"], "B", admin=True))
        self.assertFalse(self.q.cancel(j["id"], "B"))
        self.assertTrue(self.q.cancel(j["id"], "A"))
        self.assertIsNone(self.q.get(j["id"], "A"))
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, j["id"])))

    def test_error(self):
        j = self.add("A", "boom")
        self.q.run_next(block=False)
        v = self.q.view(j)
        self.assertEqual(v["state"], "error")
        self.assertIn("壞掉了", v["error"])

    def test_cancel_queued(self):
        j = self.add("A", "x")
        self.assertTrue(self.q.cancel(j["id"], "A"))
        self.assertFalse(self.q.run_next(block=False))
        self.assertEqual(self.ran, [])

    def test_restart_resumes(self):
        """主機重開：排隊中的、做到一半的都接著做；做完的結果還在。"""
        done = self.add("A", "d")
        self.q.run_next(block=False)
        q1 = self.add("A", "q1")
        mid = self.add("B", "mid")
        mid["state"], mid["started"] = "running", time.time()   # 模擬做到一半就關機
        self.q._save(mid)
        q2 = J.Jobs(self.tmp.name, self.run, log=lambda *_: None)
        self.assertEqual(q2.jobs[done["id"]]["state"], "done")
        self.assertEqual(q2.jobs[mid["id"]]["state"], "queued")
        self.assertEqual(q2.jobs[q1["id"]]["state"], "queued")
        while q2.run_next(block=False):
            pass
        self.assertEqual(sorted(x[1] for x in self.ran), ["d", "mid", "q1"])

    def test_limit_per_device(self):
        for i in range(J.MAX_PER_DEVICE):
            self.add("A", f"n{i}")
        with self.assertRaises(J.QueueFull):
            self.add("A", "too many")
        self.add("B", "ok")   # 別台不受影響

    def test_failed_upload_leaves_nothing(self):
        def bad(path):
            raise ConnectionError("上傳中斷")
        with self.assertRaises(ConnectionError):
            self.q.submit("A", {"size": 1}, bad)
        self.assertEqual(os.listdir(self.tmp.name), [])

    def test_preview_and_wait_estimate(self):
        """health 的預估：前面幾筆、要等多久（照錄音長度）。"""
        self.assertEqual(self.q.preview("A")["ahead"], 0)
        self.add("A", "long", duration=600)
        p = self.q.preview("B")
        self.assertEqual((p["queue"], p["ahead"]), (1, 1))
        self.assertGreaterEqual(p["wait_sec"], 600 * self.q.stats["ratio"])

    def test_wait_returns_when_done(self):
        """舊的 /v1/process：等到做完。"""
        j = self.add("A", "w")
        t = threading.Thread(target=lambda: (time.sleep(0.2), self.q.run_next(block=False)))
        t.start()
        got = self.q.wait(j["id"], 5)
        t.join()
        self.assertEqual(got["state"], "done")
        self.q.cancel(j["id"], "A")
        self.assertEqual(self.q.wait(j["id"], 1)["state"], "error")   # 已刪：不會卡住


if __name__ == "__main__":
    unittest.main()
