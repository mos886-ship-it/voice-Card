# phone_module.py
import time

class MockPhoneApp:
    """
    模擬 Android 手機端的行為：
    1. 接收手錶指令
    2. 執行錄音 (模擬)
    3. 執行 AI 處理管線 (Transcription + Summary)
    4. 將結果回傳給手錶
    """
    def __init__(self):
        self.watch = None
        self.is_recording = False
        self.current_file = ""

    def set_watch(self, watch_instance):
        self.watch = watch_instance

    def receive_command(self, command):
        if command == "START_RECORDING":
            print("[PHONE] 📱 [COMMAND] 收到手錶指令：啟動錄音。")
            self.start_recording_service()

    def start_recording_service(self):
        self.is_recording = True
        self.current_file = f"audio_{int(time.time())}.wav"
        print(f"[PHONE] 📱 [SERVICE] 錄音服務已啟動。檔名: {self.current_file}")
        
        # 模擬錄音過程
        time.sleep(3) 
        self.stop_recording_service()

    def stop_recording_service(self):
        self.is_recording = False
        print(f"[PHONE] 📱 [SERVICE] 錄音結束。")
        self.run_ai_pipeline(self.current_file)

    def run_ai_pipeline(self, file_path):
        print(f"[PHONE] 🤖 [AI] 正在處理檔案: {file_path}")
        print("[PHONE] 🤖 [AI] Step 1: 語音轉文字 (Transcription)...")
        time.sleep(2)
        print("[PHONE] 🤖 [AI] Step 2: 講者辨識 (Diarization)...")
        time.sleep(1)
        print("[PHONE] 🤖 [AI] Step 3: 生成智慧摘要 (Summarization)...")
        time.sleep(2)

        # 模擬 AI 產出的結構化數據 (JSON 格式)
        ai_output = {
            "topic": "產品開發進度會議",
            "key_points": [
                "決定下週進入 V2 測試階段",
                "分配任務給 Wear-dev 與 Backend-dev",
                "確認使用 Python 作為測試模擬器"
            ],
            "action_items": [
                {"task": "完成 Wear OS 通訊模組", "owner": "Wear-dev"},
                {"task": "撰寫 AI 摘要 Prompt", "owner": "Product-Designer"}
            ]
        }
        
        print("[PHONE] ✅ [AI] 處理完畢，準備回傳結果。")
        self.watch.view_summary(ai_output)
