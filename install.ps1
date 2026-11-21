<#
  WristAI 一鍵安裝 / 看 log
  用法：
    .\install.ps1            # 編譯並安裝到所有已連線的手機與手錶（自動分辨）
    .\install.ps1 -NoBuild   # 不重新編譯，直接安裝
    .\install.ps1 -Logs      # 安裝後持續顯示 WristAI 的 log（Ctrl+C 結束）
#>
param([switch]$NoBuild, [switch]$Logs)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { throw "找不到 adb，請先安裝 Android SDK Platform-Tools" }

if (-not $NoBuild) {
    & .\gradlew.bat :app:assembleDebug :wear:assembleDebug -q
    if ($LASTEXITCODE -ne 0) { throw "編譯失敗" }
}
$phoneApk = 'app\build\outputs\apk\debug\app-debug.apk'
$watchApk = 'wear\build\outputs\apk\debug\wear-debug.apk'

$serials = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' } | ForEach-Object { ($_ -split '\t')[0] }
if (-not $serials) { throw "沒有已連線的設備。手機：開 USB 偵錯並接線；手錶：開無線偵錯後 adb pair / adb connect" }

foreach ($s in $serials) {
    $isWatch = (& $adb -s $s shell getprop ro.build.characteristics) -match 'watch'
    $apk = if ($isWatch) { $watchApk } else { $phoneApk }
    $kind = if ($isWatch) { '手錶' } else { '手機' }
    Write-Host "→ $kind $s 安裝 $apk"
    & $adb -s $s install -r -g $apk
    if ($LASTEXITCODE -ne 0) { throw "$s 安裝失敗" }
}
Write-Host "完成。" -ForegroundColor Green

if ($Logs) {
    foreach ($s in $serials) { & $adb -s $s logcat -c }
    $tags = 'RecordingService PhoneLink AudioReceiver ProcessMeetingWorker SpeechTranscriber AndroidRuntime:E'
    $jobs = foreach ($s in $serials) {
        Start-Job -ArgumentList $adb, $s, $tags {
            param($adb, $s, $tags)
            & $adb -s $s logcat -v time -s ($tags -split ' ') | ForEach-Object { "[$s] $_" }
        }
    }
    try { while ($true) { $jobs | Receive-Job; Start-Sleep -Milliseconds 300 } }
    finally { $jobs | Stop-Job; $jobs | Remove-Job }
}
