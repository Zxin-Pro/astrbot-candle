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

# --- EDIT THIS LINE: your JDK 21 bin directory -----------------------
$JDK = 'C:\Users\zjh19\.dsh-jdks\jdk-21.0.12.1+1\bin'
# ---------------------------------------------------------------------

$ROOT = $PSScriptRoot
if (-not $ROOT) { $ROOT = (Get-Location).Path }
Set-Location $ROOT

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
$xml = Get-Content $mf -Raw
$xml2 = $xml -replace '\s+android:debuggable="true"', ''
if ($xml2 -ne $xml) {
  [System.IO.File]::WriteAllText($mf, $xml2, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host 'manifest: removed android:debuggable'
} else {
  Write-Host 'manifest: debuggable already absent'
}
Remove-Item "$ROOT\_apkwork\smali_classes30" -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item "$ROOT\ui\smali" "$ROOT\_apkwork\smali_classes30" -Recurse -Force
New-Item -ItemType Directory -Force -Path "$ROOT\_apkwork\assets\qqbot" | Out-Null
Copy-Item "$ROOT\ui\assets\qqbot\index.html" "$ROOT\_apkwork\assets\qqbot\index.html" -Force
Write-Host 'smali + asset copied'

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
