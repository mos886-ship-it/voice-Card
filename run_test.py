# run_test.py
from phone_module import MockPhoneApp
from watch_module import MockWatch

def main():
    print("================================================")
    print("🚀 Wrist-AI 系統集成測試啟動 (V1 Prototype)")
    print("================================================")

    # 1. 初始化硬體與 App 實例
    phone = MockPhoneApp()
    watch = MockWatch(phone)

    # 2. 建立裝置間的連動 (Pairing)
    phone.set_watch(watch)

    # 3. 開始模擬使用者操作
    print("\n[SCENARIO] 使用者在會議中，按下手錶按鈕...")
    watch.press_start_button()

    summary = watch.last_summary
    if summary is None:
        raise AssertionError("手錶未收到摘要")
    if summary.get("topic") != "產品開發進度會議":
        raise AssertionError("手錶收到的摘要主題與測試情境不符")
    if not summary.get("key_points") or not summary.get("action_items"):
        raise AssertionError("手錶收到的摘要缺少重點或待辦事項")
    if phone.is_recording or watch.is_recording:
        raise AssertionError("測試結束後仍有裝置處於錄音狀態")

    print("\n================================================")
    print("🎉 端對端模擬測試通過。")
    print("================================================")

if __name__ == "__main__":
    main()
