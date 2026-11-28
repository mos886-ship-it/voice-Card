"""fetchurl 的測試（不連網路：用假的 yt-dlp 執行結果）。用法：python local-ai/fetchurl_test.py"""
import json
import os
import shutil
import tempfile
import unittest
from unittest import mock

import fetchurl


class FetchTest(unittest.TestCase):
    def test_bad_url(self):
        for u in ("", "ftp://x", "javascript:alert(1)", "not a url"):
            with self.assertRaises(ValueError):
                fetchurl.fetch(u)

    def test_no_ytdlp(self):
        with mock.patch.object(fetchurl, "ytdlp_cmd", return_value=None):
            with self.assertRaises(FileNotFoundError):
                fetchurl.fetch("https://example.com/v")

    def test_success(self):
        def runner(cmd, **kw):
            out = cmd[cmd.index("-o") + 1].replace("%(ext)s", "m4a")
            with open(out, "wb") as f:
                f.write(b"data")
            tfile = cmd[cmd.index("--print-to-file") + 2]
            with open(tfile, "w", encoding="utf-8") as f:
                f.write("課堂 錄影\n")
            return mock.Mock(returncode=0, stdout="", stderr="")
        with mock.patch.object(fetchurl, "ytdlp_cmd", return_value="/bin/yt-dlp"):
            path, title, mime, tmp = fetchurl.fetch("https://www.youtube.com/watch?v=x", runner=runner)
        try:
            self.assertTrue(path.endswith("audio.m4a"))
            self.assertEqual("課堂 錄影", title)
            self.assertEqual("audio/mp4", mime)
            self.assertEqual("課堂 錄影", __import__("base64").b64decode(fetchurl.title_header(title)).decode("utf-8"))
        finally:
            shutil.rmtree(tmp)

    def test_failure_cleans_up(self):
        made = []
        def runner(cmd, **kw):
            made.append(os.path.dirname(cmd[cmd.index("-o") + 1]))
            return mock.Mock(returncode=1, stdout="", stderr="ERROR: Video unavailable")
        with mock.patch.object(fetchurl, "ytdlp_cmd", return_value="/bin/yt-dlp"):
            with self.assertRaises(RuntimeError) as e:
                fetchurl.fetch("https://www.youtube.com/watch?v=x", runner=runner)
        self.assertIn("Video unavailable", str(e.exception))
        self.assertFalse(os.path.exists(made[0]))


class FeedbackTest(unittest.TestCase):
    def test_append_and_filter(self):
        d = tempfile.mkdtemp()
        try:
            p = os.path.join(d, "feedback.jsonl")
            fetchurl.save_feedback(p, {"kind": "translation", "original": "你好", "translated": "Hello", "secret": "x"}, "dev1", now=1)
            fetchurl.save_feedback(p, {"note": "翻錯了"}, now=2)
            rows = [json.loads(l) for l in open(p, encoding="utf-8")]
            self.assertEqual(2, len(rows))
            self.assertNotIn("secret", rows[0])
            self.assertEqual("dev1", rows[0]["device"])
            with self.assertRaises(ValueError):
                fetchurl.save_feedback(p, {"kind": "translation"})
        finally:
            shutil.rmtree(d)


if __name__ == "__main__":
    unittest.main()
