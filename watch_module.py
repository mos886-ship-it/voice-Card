# watch_module.py

class MockWatch:
    """
    模擬 Wear OS 端的行為：
    1. 發送指令給手機
    2. 接收並顯示摘要結果
    """
    def __init__(self, phone_app):
        self.phone_app = phone_app
        self.is_recording = False
        self.last_summary = None

    def press_start_button(self):
        print("\n[WATCH] ⌚ [USER ACTION] 按下手錶上的錄音按鈕...")
        self.is_recording = True
        self.last_summary = None
        # 透過 Data Layer API 發送指令給手機
        self.phone_app.receive_command("START_RECORDING")

    def view_summary(self, summary_data):
        self.is_recording = False
        self.last_summary = summary_data
        print("\n[WATCH] ⌚ [NOTIFICATION] 收到摘要推送！")
        print(f"--- 📝 {summary_data['topic']} ---")
        print("重點摘要：")
        for point in summary_data['key_points']:
            print(f"  • {point}")
        print("---------------------------")
        print("[WATCH] ⌚ 已完成資訊顯示。")
