"""大D的主機：工作佇列（多人共用一台主機時用）。

主機一次只能處理一筆（同一顆 GPU）。以前 App 送出錄音後要一直連著等到報告出來，
很多人同時送時後面的人只能空等，App 也不知道前面還有幾筆。現在改成：

- 送出（POST /v1/jobs）→ 錄音先存到主機硬碟，馬上拿到工作編號、前面還有幾筆、大約要等多久；
- App 之後用 GET /v1/jobs/<id> 查進度，做完拿結果，拿到後 DELETE 刪掉；
- 輪流處理：每台裝置輪一筆（不是先送先做），一個人一次丟 10 筆也不會把別人卡住；
- 工作存在硬碟（jobs/<id>/），主機重開機後沒做完的會接著做；做完的結果留 24 小時沒人拿就刪掉；
- 每台裝置最多排 MAX_PER_DEVICE 筆。

舊的 POST /v1/process（連著等結果）照樣可以用：它也是排進同一個佇列，再等結果。
這個檔案不載入任何模型，可以單獨測試（jobs_test.py）。
"""
import json
import os
import secrets
import shutil
import threading
import time

KEEP_SECONDS = 24 * 3600      # 做完的結果留多久（沒人拿就刪）
MAX_PER_DEVICE = 20           # 每台裝置最多排幾筆
MIN_FREE_BYTES = 1 << 30      # 收檔後至少要留 1 GB 硬碟空間
DONE_STATES = ("done", "error")


class QueueFull(Exception):
    """排太多筆 / 硬碟不夠：訊息直接給使用者看。"""


class Jobs:
    def __init__(self, root, run, log=print):
        """[run](job, 檔案路徑) → {"text", "model"}；失敗丟例外（訊息給使用者看）。"""
        self.root = root
        os.makedirs(root, exist_ok=True)
        try:
            os.chmod(root, 0o700)   # 錄音只有自己讀得到
        except OSError:
            pass
        self.run = run
        self.log = log
        self.cond = threading.Condition()
        self.jobs = {}            # id → 工作（dict，同 job.json）
        self.served = {}          # 裝置 → 最近一次輪到它的序號（輪流用）
        self.counter = 0
        self.stats = {"ratio": 0.5, "overhead": 20.0}   # 預估：處理秒數 ≈ overhead + 錄音秒數 × ratio
        self._load()

    def start(self):
        threading.Thread(target=self._worker, daemon=True, name="jobs").start()
        return self

    # ---------------- 硬碟 ----------------

    def _dir(self, jid):
        return os.path.join(self.root, jid)

    def input_path(self, jid):
        return os.path.join(self._dir(jid), "input")

    def _save(self, j):
        path = os.path.join(self._dir(j["id"]), "job.json")
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(j, f, ensure_ascii=False)
        os.replace(tmp, path)

    def _remove(self, j):
        self.jobs.pop(j["id"], None)
        shutil.rmtree(self._dir(j["id"]), ignore_errors=True)

    def _load(self):
        try:
            with open(os.path.join(self.root, "stats.json"), encoding="utf-8") as f:
                self.stats.update(json.load(f))
        except (OSError, ValueError):
            pass
        for name in os.listdir(self.root):
            path = os.path.join(self.root, name, "job.json")
            try:
                with open(path, encoding="utf-8") as f:
                    j = json.load(f)
            except (OSError, ValueError):
                if os.path.isdir(os.path.join(self.root, name)):
                    shutil.rmtree(os.path.join(self.root, name), ignore_errors=True)   # 上傳到一半就斷的
                continue
            if j.get("cancel"):
                shutil.rmtree(os.path.join(self.root, name), ignore_errors=True)
                continue
            if j.get("state") == "running":   # 主機重開時正在做的：重新排隊
                j["state"] = "queued"
                j.pop("started", None)
            self.jobs[j["id"]] = j
        n = sum(1 for j in self.jobs.values() if j["state"] == "queued")
        if n:
            self.log(f"jobs: {n} queued from before restart")

    # ---------------- 送出 ----------------

    def check_room(self, dev, size):
        """收檔前先檢查（還沒讀上傳的內容）：排太多 / 硬碟不夠 → QueueFull。"""
        with self.cond:
            mine = sum(1 for j in self.jobs.values() if j["dev"] == dev and j["state"] in ("queued", "running"))
        if mine >= MAX_PER_DEVICE:
            raise QueueFull(f"這台裝置已經有 {mine} 筆在大D的主機排隊，等前面的做完再送")
        if shutil.disk_usage(self.root).free < size + MIN_FREE_BYTES:
            raise QueueFull("大D的主機硬碟空間不夠，請主機管理者清出空間")

    def submit(self, dev, params, write):
        """[write](路徑) 把上傳的內容寫進檔案；寫完才排進佇列。回傳工作。"""
        self.check_room(dev, int(params.get("size") or 0))
        jid = time.strftime("%Y%m%d%H%M%S") + "-" + secrets.token_hex(4)
        os.makedirs(self._dir(jid), mode=0o700)
        try:
            write(self.input_path(jid))
        except BaseException:
            shutil.rmtree(self._dir(jid), ignore_errors=True)
            raise
        j = {**params, "id": jid, "dev": dev, "state": "queued", "created": time.time()}
        with self.cond:
            self.jobs[jid] = j
            self._save(j)
            self.cond.notify_all()
        return j

    # ---------------- 排序（輪流） ----------------

    def _order(self, extra=None):
        """排隊中的工作，照實際會被處理的順序。每台裝置輪一筆：最久沒輪到的裝置先；同一台照送出順序。"""
        queued = sorted((j for j in self.jobs.values() if j["state"] == "queued"), key=lambda j: j["created"])
        if extra:
            queued.append(extra)
        per = {}
        for j in queued:
            per.setdefault(j["dev"], []).append(j)
        served = dict(self.served)
        tick = self.counter
        out = []
        while per:
            dev = min(per, key=lambda d: (served.get(d, -1), per[d][0]["created"]))
            out.append(per[dev].pop(0))
            tick += 1
            served[dev] = tick
            if not per[dev]:
                del per[dev]
        return out

    def _estimate(self, j):
        """這筆大約要處理幾秒。文字輸入沒有長度，當 60 秒。"""
        if j.get("text_input"):
            return 60.0
        return self.stats["overhead"] + float(j.get("duration") or 0) * self.stats["ratio"]

    def _running(self):
        return next((j for j in self.jobs.values() if j["state"] == "running"), None)

    def _wait_before(self, order, idx):
        """排在第 idx 個之前要等幾秒（含正在做的那筆還剩多少）。"""
        wait = 0.0
        r = self._running()
        if r:
            wait += max(10.0, self._estimate(r) - (time.time() - r["started"]))
        return wait + sum(self._estimate(j) for j in order[:idx])

    def view(self, j):
        """給 App 看的狀態（不含裝置 id、檔案路徑）。"""
        with self.cond:
            out = {"id": j["id"], "state": j["state"], "created": int(j["created"])}
            if j["state"] == "queued":
                order = self._order()
                idx = next((i for i, x in enumerate(order) if x["id"] == j["id"]), len(order))
                out["ahead"] = idx + (1 if self._running() else 0)
                out["wait_sec"] = int(self._wait_before(order, idx) + self._estimate(j))
            elif j["state"] == "running":
                out["ahead"] = 0
                out["started"] = int(j["started"])
                out["wait_sec"] = int(max(10.0, self._estimate(j) - (time.time() - j["started"])))
            elif j["state"] == "done":
                out["text"], out["model"] = j.get("text", ""), j.get("model", "")
            else:
                out["error"] = j.get("error", "")
            return out

    def preview(self, dev, duration=0.0):
        """現在送一筆的話：前面有幾筆、大約要等多久（health 用，App 送出前可以先告訴使用者）。"""
        with self.cond:
            fake = {"id": "", "dev": dev, "created": time.time(), "duration": duration, "state": "queued"}
            order = self._order(fake)
            idx = next(i for i, x in enumerate(order) if x is fake)
            ahead = idx + (1 if self._running() else 0)
            n = sum(1 for j in self.jobs.values() if j["state"] in ("queued", "running"))
            return {"queue": n, "ahead": ahead, "wait_sec": int(self._wait_before(order, idx))}

    # ---------------- 查詢 / 取消 ----------------

    def get(self, jid, dev, admin=False):
        """只有送出的那台（或管理者）看得到。"""
        with self.cond:
            j = self.jobs.get(jid)
            return j if j and (admin or j["dev"] == dev) else None

    def cancel(self, jid, dev, admin=False):
        """排隊中 → 取消；做完的 → 刪掉結果；正在做 → 做完後直接丟掉。找不到 → False。"""
        with self.cond:
            j = self.jobs.get(jid)
            if not j or not (admin or j["dev"] == dev):
                return False
            if j["state"] == "running":
                j["cancel"] = True
                self._save(j)
            else:
                self._remove(j)
            self.cond.notify_all()
            return True

    def wait(self, jid, timeout):
        """等這筆做完（舊的 /v1/process 用）。做完回傳工作，逾時回 None。"""
        end = time.time() + timeout
        with self.cond:
            while True:
                j = self.jobs.get(jid)
                if j is None:   # 被取消 / 刪掉了
                    return {"id": jid, "state": "error", "error": "這筆工作被取消了", "created": 0}
                if j["state"] in DONE_STATES:
                    return j
                left = end - time.time()
                if left <= 0:
                    return None
                self.cond.wait(min(left, 5))

    # ---------------- 處理 ----------------

    def _cleanup(self):
        now = time.time()
        for j in list(self.jobs.values()):
            if j["state"] in DONE_STATES and now - j.get("finished", now) > KEEP_SECONDS:
                self._remove(j)

    def run_next(self, block=True):
        """處理下一筆（worker 執行緒用；測試可以直接呼叫）。沒有排隊的 → False。"""
        with self.cond:
            self._cleanup()
            order = self._order()
            if not order:
                if block:
                    self.cond.wait(60)
                return False
            j = order[0]
            self.counter += 1
            self.served[j["dev"]] = self.counter
            j["state"], j["started"] = "running", time.time()
            self._save(j)
        try:
            res = self.run(j, self.input_path(j["id"]))
            upd = {"state": "done", "text": res["text"], "model": res["model"]}
        except Exception as e:  # noqa: BLE001
            upd = {"state": "error", "error": str(e) or e.__class__.__name__}
        with self.cond:
            j.update(upd)
            j["finished"] = time.time()
            try:
                os.remove(self.input_path(j["id"]))   # 錄音做完就刪，只留結果
            except OSError:
                pass
            elapsed = j["finished"] - j["started"]
            dur = float(j.get("duration") or 0)
            if j["state"] == "done" and not j.get("text_input") and dur >= 30:
                r = max(0.05, min(5.0, (elapsed - self.stats["overhead"]) / dur))
                self.stats["ratio"] = round(0.7 * self.stats["ratio"] + 0.3 * r, 4)
                try:
                    with open(os.path.join(self.root, "stats.json"), "w", encoding="utf-8") as f:
                        json.dump(self.stats, f)
                except OSError:
                    pass
            self.log(f"job {j['id']} {j['state']} in {elapsed:.1f}s")
            if j.get("cancel"):
                self._remove(j)
            else:
                self._save(j)
            self.cond.notify_all()
        return True

    def _worker(self):
        while True:
            try:
                self.run_next()
            except Exception as e:  # noqa: BLE001   佇列本身出錯也不能讓執行緒停掉
                self.log(f"jobs worker error: {e}")
                time.sleep(5)
