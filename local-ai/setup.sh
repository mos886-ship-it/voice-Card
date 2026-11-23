#!/bin/bash
# 大D 自架 AI 服務：在這台 Mac 安裝並設定開機（登入）自動啟動。
# 用法：bash local-ai/setup.sh   （在 repo 根目錄執行）
# 會建立 ~/dylan-local（Python 環境、模型快取、存取碼 config.json），模型第一次啟動時下載（約 6.5 GB）。
set -e
SRC="$(cd "$(dirname "$0")" && pwd)"
DEST="$HOME/dylan-local"
mkdir -p "$DEST/samples"
cp "$SRC/server.py" "$SRC/pairing.py" "$SRC/asr_test.py" "$SRC/llm_test.py" "$DEST/"
[ -d "$DEST/venv" ] || /usr/bin/python3 -m venv "$DEST/venv"
"$DEST/venv/bin/pip" install -q --upgrade pip
"$DEST/venv/bin/pip" install -q -r "$SRC/requirements.txt"
mkdir -p "$HOME/Library/LaunchAgents"
sed "s|__HOME__|$HOME|g" "$SRC/com.dylan.localai.plist" > "$HOME/Library/LaunchAgents/com.dylan.localai.plist"
launchctl bootout "gui/$(id -u)/com.dylan.localai" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$HOME/Library/LaunchAgents/com.dylan.localai.plist"
echo "服務啟動中（第一次要下載模型，可能要 10 分鐘以上）。紀錄：$DEST/server.log"
echo "存取碼在 $DEST/config.json 的 token（貼到 App 設定頁「這台 Mac（自架）」）。"
