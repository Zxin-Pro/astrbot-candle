# =====================================================================
# build-ui.ps1 - build the native console (WebView UI) intermediate APK
#
#   javac -> d8 -> DexToSmali -> apktool b
#
# Usage:  powershell -ExecutionPolicy Bypass -File build-ui.ps1
#
# NOTE 1: keep this file pure ASCII. Windows PowerShell 5.1 decodes a
#         BOM-less .ps1 as ANSI/GBK, so non-ASCII literals get mangled.
# NOTE 2: this script and build-apk.ps1 MUST run serially - both write
#         out\termux-ui-unsigned.apk. Always build-ui first.
# =====================================================================
$ErrorActionPreference = 'Continue'

# --- JDK bin directory ----------------------------------------------
# Prefer $env:JAVA_HOME, else the first javac.exe on PATH. Any JDK 17+
# works: javac targets 11 and d8/apktool only need a modern java.exe.
$JDK = ''
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
  $JDK = Join-Path $env:JAVA_HOME 'bin'
} else {
  $jc = Get-Command javac.exe -ErrorAction SilentlyContinue
  if ($jc) { $JDK = Split-Path $jc.Source -Parent }
}
if (-not $JDK) { Write-Host 'MISSING javac: set JAVA_HOME or put javac.exe on PATH'; exit 1 }

Write-Host ("JDK: " + $JDK)

$ROOT = $PSScriptRoot
if (-not $ROOT) { $ROOT = (Get-Location).Path }
Set-Location $ROOT

# 0) (re)compile the java helper tools (tools\*.java -> tools\*.class).
#    They are gitignored build artifacts: a fresh clone has none, and classes
#    built by a newer JDK fail on an older runtime (UnsupportedClassVersionError),
#    which silently leaves ui\smali from the previous build in place.
$TOOLSRC = @(Get-ChildItem "$ROOT\tools" -Filter *.java | ForEach-Object { $_.FullName })
$needTools = $false
foreach ($f in $TOOLSRC) {
  $cls = Join-Path "$ROOT\tools" ((Split-Path $f -Leaf) -replace '\.java$', '.class')
  if (-not (Test-Path $cls)) { $needTools = $true }
  elseif ((Get-Item $f).LastWriteTime -gt (Get-Item $cls).LastWriteTime) { $needTools = $true }
}
if ($needTools -and $TOOLSRC.Count -gt 0) {
  Write-Host ("tools: compiling " + $TOOLSRC.Count + " sources")
  & "$JDK\javac.exe" -encoding UTF-8 -nowarn -cp "$ROOT\_sdk\apktool.jar" -d "$ROOT\tools" $TOOLSRC 2>$null
  Write-Host ("tools javac rc=" + $LASTEXITCODE)
  if ($LASTEXITCODE -ne 0) { Write-Host 'TOOLS COMPILE FAIL'; exit 1 }
} else {
  Write-Host 'tools: up to date'
}

$JAR = "$ROOT\_sdk\platform\android-34\android.jar"
if (-not (Test-Path $JAR)) { Write-Host "MISSING android.jar: $JAR"; exit 1 }
if (-not (Test-Path "$ROOT\_sdk\apktool.jar")) { Write-Host "MISSING apktool.jar"; exit 1 }
if (-not (Test-Path "$ROOT\_sdk\bt\android-14\lib\d8.jar")) { Write-Host "MISSING d8.jar"; exit 1 }
if (-not (Test-Path "$JDK\javac.exe")) { Write-Host "MISSING javac: $JDK"; exit 1 }

# 1) javac
$SRC = @(Get-ChildItem "$ROOT\ui\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Write-Host "javac: $($SRC.Count) sources"
Remove-Item "$ROOT\ui\classes" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$ROOT\ui\classes" | Out-Null
& "$JDK\javac.exe" -encoding UTF-8 -source 11 -target 11 -g -nowarn -cp $JAR -d "$ROOT\ui\classes" $SRC 2>$null
Write-Host "javac rc=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { Write-Host 'JAVAC FAIL'; exit 1 }

# 2) d8
$CLASSES = @(Get-ChildItem "$ROOT\ui\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName })
Write-Host "d8: $($CLASSES.Count) classes"
Remove-Item "$ROOT\ui\dex" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$ROOT\ui\dex" | Out-Null
& "$JDK\java.exe" -cp "$ROOT\_sdk\bt\android-14\lib\d8.jar" com.android.tools.r8.D8 --min-api 24 --lib $JAR --output "$ROOT\ui\dex" $CLASSES 2>$null
Write-Host "d8 rc=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { Write-Host 'D8 FAIL'; exit 1 }

# 3) dex -> smali, and copy the console HTML into the apktool workspace
Remove-Item "$ROOT\ui\smali" -Recurse -Force -ErrorAction SilentlyContinue
& "$JDK\java.exe" -cp "$ROOT\_sdk\apktool.jar;$ROOT\tools" DexToSmali "$ROOT\ui\dex\classes.dex" "$ROOT\ui\smali" 34 2>$null
Write-Host "dextosmali rc=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { Write-Host 'DEXTOSMALI FAIL'; exit 1 }
# A failed disassembly used to be swallowed: the build then repacked the PREVIOUS
# ui\smali, so the APK shipped dex and smali from different commits with no error.
if (-not (Test-Path "$ROOT\ui\smali")) { Write-Host 'MISSING ui\smali after DexToSmali'; exit 1 }
if (-not (Test-Path "$ROOT\_apkwork\apktool.yml")) { Write-Host 'MISSING _apkwork - run apktool d first (see docs/BUILD.md)'; exit 1 }

# 3b) version stamp: the VERSION file at the repo root is the single source of truth
#     versionCode = MAJOR*10000 + MINOR*100 + PATCH   (see CHANGELOG.md)
$VER = '1.0.0'
if (Test-Path "$ROOT\VERSION") { $VER = (Get-Content "$ROOT\VERSION" -Raw).Trim() }
if ($VER -notmatch '^(\d+)\.(\d+)\.(\d+)$') { Write-Host "BAD VERSION file: $VER"; exit 1 }
$VC = ([int]$Matches[1]) * 10000 + ([int]$Matches[2]) * 100 + ([int]$Matches[3])
$yml = Get-Content "$ROOT\_apkwork\apktool.yml" -Raw
$yml = $yml -replace 'versionCode: \d+', "versionCode: $VC"
$yml = $yml -replace 'versionName: .*', "versionName: $VER"
[System.IO.File]::WriteAllText("$ROOT\_apkwork\apktool.yml", $yml, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "version $VER ($VC)"
# 3c) 小米对「debug 包 / 被重打包」的应用会做安装拦截（v1.1.28 起去掉这个信号）：
#     从 manifest 里删掉 android:debuggable="true"，重复执行安全（幂等）。
#     注意：不要加 android:privacyPolicy —— 该属性在 Android 里不存在，aapt2 会直接报错。
$mf = "$ROOT\_apkwork\AndroidManifest.xml"
$xml = Get-Content $mf -Raw -Encoding UTF8   # 必须显式 UTF8：无 BOM 的 XML 会被 PS 5.1 当 GBK 读，中文 label 会被写坏
$xml2 = $xml -replace '\s+android:debuggable="true"', ''
if ($xml2 -ne $xml) {
  [System.IO.File]::WriteAllText($mf, $xml2, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host 'manifest: removed android:debuggable'
} else {
  Write-Host 'manifest: debuggable already absent'
}
# 3c2) Android 13+ 通知权限：清单里没有 POST_NOTIFICATIONS，应用连申请都没法申请，
#      常驻守护通知会静默消失（重要性 NONE，实测）。这里幂等补一行，重复执行安全。
if ($xml2 -notmatch 'android\.permission\.POST_NOTIFICATIONS') {
  $boot = '<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>'
  if ($xml2.Contains($boot)) {
    $xml2 = $xml2.Replace($boot, $boot + "`r`n<uses-permission android:name=`"android.permission.POST_NOTIFICATIONS`"/>")
    [System.IO.File]::WriteAllText($mf, $xml2, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host 'manifest: added POST_NOTIFICATIONS'
  } else {
    Write-Host 'manifest: RECEIVE_BOOT_COMPLETED anchor missing - POST_NOTIFICATIONS NOT added'
  }
} else {
  Write-Host 'manifest: POST_NOTIFICATIONS already present'
}
# 3c3) AV heuristics (v1.2.3): Xiaomi/Tencent flag this as a.gray.devicemaster.a because it is a
#      terminal app carrying several high-risk permissions plus an exported command service.
#      None of these permissions are used anywhere in the sources (grep-verified); the RunCommand
#      IPC service is Termux legacy for third-party apps and is not needed here. Idempotent.
$drop = @(
  'android.permission.READ_LOGS',
  'android.permission.DUMP',
  'android.permission.WRITE_SECURE_SETTINGS',
  'android.permission.PACKAGE_USAGE_STATS',
  'android.permission.MANAGE_EXTERNAL_STORAGE',
  'android.permission.SYSTEM_ALERT_WINDOW',
  'com.android.alarm.permission.SET_ALARM'
)
$xml3 = Get-Content $mf -Raw -Encoding UTF8
$orig3 = $xml3
$removed = 0
foreach ($perm in $drop) {
  $pat = '\s*<uses-permission android:name="' + [regex]::Escape($perm) + '"\s*/>'
  $n = [regex]::Matches($xml3, $pat).Count
  if ($n -gt 0) { $xml3 = [regex]::Replace($xml3, $pat, ''); $removed += $n }
}
# The RUN_COMMAND <permission> declaration stays: TermuxOpenReceiver$ContentProvider still references
# it, and dropping a permission that something points at is worse than keeping it.
# RunCommandService is the Termux IPC entry point (any app with the RUN_COMMAND permission could ask us
# to run shell commands). Nothing in this app uses it, and an exported command service is exactly the
# kind of thing Xiaomi's scanner calls a.gray.devicemaster.a. Unexport it in place.
$svcPat = '<service\s+[^>]*RunCommandService[^>]*>'
$svc = [regex]::Match($xml3, $svcPat)
if ($svc.Success -and ($svc.Value -match 'android:exported="true"')) {
  $fixed = $svc.Value -replace 'android:exported="true"', 'android:exported="false"'
  $xml3 = $xml3.Replace($svc.Value, $fixed)
  $removed++
}
if ($xml3 -ne $orig3) {
  [System.IO.File]::WriteAllText($mf, $xml3, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host ("manifest: trimmed {0} dangerous entries, RunCommandService unexported" -f $removed)
} else {
  Write-Host 'manifest: nothing to trim (already clean)'
}
Remove-Item "$ROOT\_apkwork\smali_classes30" -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item "$ROOT\ui\smali" "$ROOT\_apkwork\smali_classes30" -Recurse -Force -ErrorAction Stop
New-Item -ItemType Directory -Force -Path "$ROOT\_apkwork\assets\qqbot" | Out-Null
Copy-Item "$ROOT\ui\assets\qqbot\index.html" "$ROOT\_apkwork\assets\qqbot\index.html" -Force
# header logo: the app's own icon, served from assets so the WebView can load it.
Copy-Item "$ROOT\ui\assets\qqbot\appicon.png" "$ROOT\_apkwork\assets\qqbot\appicon.png" -Force
Write-Host 'smali + asset copied'

# 3c-2) rename the Termux package id inside the decoded smali (com.termux -> zhuxin.astrbot).
#     The dex only carries "com/termux" class descriptors, so the dotted name lives in the
#     string constants (TermuxConstants etc.) and in /data/data/com.termux paths.
#     Idempotent: no-op once the strings are already rewritten.
& python (Join-Path $ROOT "tools\patch_smali.py") "$ROOT\_apkwork"
if ($LASTEXITCODE -ne 0) { Write-Host 'SMALI PATCH FAIL'; exit 1 }

# 3d) app icon: copy ui\assets\icon\res into _apkwork\res before apktool b.
#     ASCII only (PowerShell 5.1 reads this file as GBK when it has no BOM).
#     Idempotent: re-running just overwrites the same files again.
$iconRoot = "$ROOT\ui\assets\icon\res"
if ((Test-Path "$ROOT\ui\assets\icon\source.png") -and (Test-Path $iconRoot)) {
  # the stock drawable/ic_foreground.xml is a vector bubble in the default config;
  # it wins over drawable-*/ic_foreground.png, so it must go.
  $fgXml = "$ROOT\_apkwork\res\drawable\ic_foreground.xml"
  if (Test-Path $fgXml) {
    Remove-Item $fgXml -Force -ErrorAction SilentlyContinue
    Write-Host '[icon] OK  removed _apkwork\res\drawable\ic_foreground.xml (vector shadows the png)'
  } else {
    Write-Host '[icon] OK  _apkwork\res\drawable\ic_foreground.xml already absent'
  }
  $iconFiles = @(Get-ChildItem $iconRoot -Recurse -File)
  $pngCount = @($iconFiles | Where-Object { $_.Extension -eq '.png' }).Count
  Write-Host ('[icon] Plan copy ' + $pngCount + ' png + background xml from ui\assets\icon\res to _apkwork\res')
  foreach ($f in $iconFiles) {
    $rel = $f.FullName.Substring($iconRoot.Length + 1)
    $dst = Join-Path "$ROOT\_apkwork\res" $rel
    $dstDir = Split-Path $dst -Parent
    if (-not (Test-Path $dstDir)) { New-Item -ItemType Directory -Force -Path $dstDir | Out-Null }
    Copy-Item $f.FullName $dst -Force
    Write-Host ('[icon]   OK ' + $rel)
  }
  $bgXml = Join-Path $iconRoot 'drawable\ic_launcher_background.xml'
  if (Test-Path $bgXml) {
    Copy-Item $bgXml "$ROOT\_apkwork\res\drawable\ic_launcher_background.xml" -Force
    Write-Host '[icon] OK  _apkwork\res\drawable\ic_launcher_background.xml = #FF1A0A12'
  }
  Write-Host ('[icon] done: ' + $pngCount + ' png + 1 background xml')
} else {
  Write-Host '[icon] missing ui\assets\icon\res or source.png, skip'
}

# 3e) widget + quick-settings tile resources and manifest entries (features 12 / 13).
#     Source of truth: ui\assets\android-res\** and ui\assets\android\widget-manifest.xml.
#     The manifest snippet lives in a UTF-8 file because PowerShell 5.1 reads a BOM-less
#     .ps1 as GBK and would corrupt the Chinese label. ASCII only in this file.
#     Idempotent: the snippet is skipped when the manifest already mentions the receiver.
$andRes = "$ROOT\ui\assets\android-res"
if (Test-Path $andRes) {
  $andFiles = @(Get-ChildItem $andRes -Recurse -File)
  foreach ($f in $andFiles) {
    $rel = $f.FullName.Substring($andRes.Length + 1)
    $dst = Join-Path "$ROOT\_apkwork\res" $rel
    $dstDir = Split-Path $dst -Parent
    if (-not (Test-Path $dstDir)) { New-Item -ItemType Directory -Force -Path $dstDir | Out-Null }
    Copy-Item $f.FullName $dst -Force
  }
  Write-Host ('[res] copied ' + $andFiles.Count + ' android res files into _apkwork\res')
} else {
  Write-Host '[res] ui\assets\android-res missing, skip'
}
$snippet = "$ROOT\ui\assets\android\widget-manifest.xml"
if ((Test-Path $snippet) -and (Test-Path $mf)) {
  $mfNow = Get-Content $mf -Raw -Encoding UTF8
  if ($mfNow -match 'QqbotWidgetProvider') {
    Write-Host '[res] manifest already has widget receiver, skip'
  } else {
    $snip = (Get-Content $snippet -Raw -Encoding UTF8) -replace '(?s)^\s*<!--.*?-->\s*', ''
    $snip = $snip.TrimEnd() + "`r`n"
    $idx = $mfNow.LastIndexOf('</application>')
    if ($idx -lt 0) { Write-Host '[res] </application> not found, manifest not patched'; exit 1 }
    $mfOut = $mfNow.Substring(0, $idx) + $snip + '    ' + $mfNow.Substring($idx)
    [System.IO.File]::WriteAllText($mf, $mfOut, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host '[res] manifest: added widget receiver + tile service'
  }
} else {
  Write-Host '[res] widget-manifest.xml missing, skip'
}

# 4) apktool b
New-Item -ItemType Directory -Force -Path "$ROOT\out" | Out-Null
& "$JDK\java.exe" -jar "$ROOT\_sdk\apktool.jar" b -f -o "$ROOT\out\termux-ui-unsigned.apk" "$ROOT\_apkwork" 2>$null
Write-Host "apktool rc=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { Write-Host 'APKTOOL FAIL'; exit 1 }
Get-Item "$ROOT\out\termux-ui-unsigned.apk" | ForEach-Object { Write-Host ('UI-APK ' + $_.Length) }
Write-Host 'BUILD-UI DONE'
