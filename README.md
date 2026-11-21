# Wrist-AI Project: Prototype V1

This repository contains the initial rapid-prototyping simulation for the **Wrist-AI** project. The goal of this project is to create a seamless "Watch-to-Phone" AI recording assistant that automates the process of recording, transcribing, and summarizing meetings.

## 🎯 Project Vision
**"Minimal Interaction, Maximum Intelligence"**
Users trigger a recording via a single button press on their Samsung Watch, while the heavy AI processing (Transcription, Diarization, and Summarization) is offloaded to the connected smartphone or cloud.

---

## 🏗️ System Architecture (Simulation)

The prototype simulates a three-tier architecture:

1.  **Wear OS Module (`watch_module.py`)**:
    *   Simulates the user interface.
    *   Handles the "Start Recording" command via a simulated Data Layer API.
    *   Displays the final AI-generated summary.

2.  **Android Phone Module (`phone_module.py`)**:
    *   Acts as the central processing hub.
    *   Simulates the recording service lifecycle.
    *   Simulates an AI Pipeline:
        *   **STT (Speech-to-Text)**
        *   **Speaker Diarization** (Who spoke when)
        *   **LLM Summarization** (Structured insights)

3.  **Test Runner (`run_test.py`)**:
    *   Orchestrates the simulation by pairing the "Watch" and "Phone" and triggering the user action.

---

## 🚀 How to Run the Simulation

To verify the logic of the end-to-end workflow without needing a physical Android environment, follow these steps:

### Prerequisites
*   Python 3.x installed on your local machine.

### Execution
Run the following command in your terminal:

```bash
python run_test.py
```

### Expected Output Trace
You should see a log sequence similar to this:
1. `[WATCH] ⌚ [USER ACTION] 按下手錶上的錄音按鈕...`
2. `[PHONE] 📱 [COMMAND] 收到手錶指令：啟動錄音。`
3. `[PHONE] 🤖 [AI] 正在處理檔案...`
4. `[PHONE] ✅ [AI] 處理完畢，準備回傳結果。`
5. `[WATCH] ⌚ [NOTIFICATION] 收到摘要推送！`
6. `[WATCH] ⌚ 📝 會議主題: 產品開發進度會議 ...`

---

## 🗺️ Roadmap

### Phase 1: Simulation (Current)
- [x] End-to-end logic verification via Python simulation.
- [x] Define data structures for AI summaries.

### Phase 2: Android/Wear OS Implementation (Next)
- [ ] Implement `Wear OS Data Layer` communication in Kotlin.
- [ ] Develop `Android Foreground Service` for reliable recording.
- [ ] Integrate `OpenAI Whisper` or `Samsung AI` for real transcription.

### Phase 3: Intelligence Enhancement
- [ ] Advanced Prompt Engineering for professional meeting reports.
- [ ] Multi-language support and cloud sync.
