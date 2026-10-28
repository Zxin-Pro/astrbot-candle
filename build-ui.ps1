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
# Do NOT add 2>$null here: it swallows javac's real errors (a missing-symbol
# error once showed up as a bare JAVAC FAIL line and looked like an env bug).
& "$JDK\javac.exe" -encoding UTF-8 -source 11 -target 11 -g -nowarn -cp $JAR -d "$ROOT\ui\classes" $SRC
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
#     versionName is VERSION verbatim and may carry a pre-release suffix ("1.3.2-beta.1"),
#     so a test build is recognisable in 设置页 and in crash reports;
#     versionCode = MAJOR*10000 + MINOR*100 + PATCH regardless of the suffix
#     (see CHANGELOG.md) -- that makes "install beta, then install the final build"
#     an in-place upgrade, no uninstall needed.
$VER = '1.0.0'
if (Test-Path "$ROOT\VERSION") { $VER = (Get-Content "$ROOT\VERSION" -Raw).Trim() }
if ($VER -notmatch '^(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z][0-9A-Za-z.-]*)?$') { Write-Host "BAD VERSION file: $VER"; exit 1 }
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
# 3c2b) 应用内更新要装 APK；前台常驻服务要两条前台服务权限。
#       1.3.4-beta.9 起灵动岛整套下线，SYSTEM_ALERT_WINDOW（悬浮窗）与 beta.8 的 POST_PROMOTIONS
#       （Live Updates 状态栏芯片）都不再有代码使用，见 3c2c 的回收逻辑 —— 一个机器人应用还挂着
#       悬浮窗权限，小米/腾讯扫描会直接往 a.gray.devicemaster 这一类上靠，删掉比留着好。
#       FOREGROUND_SERVICE 是 API 28+ 起 startForeground 的前提；FOREGROUND_SERVICE_SPECIAL_USE
#       是 API 34+ 声明 foregroundServiceType="specialUse" 时要配的权限。两条都是普通权限，
#       不弹运行时授权，装上就有。
foreach ($need in @('android.permission.REQUEST_INSTALL_PACKAGES', 'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE')) {
  $mfx = Get-Content $mf -Raw -Encoding UTF8
  if ($mfx -notmatch [regex]::Escape($need)) {
    $boot2 = '<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>'
    if ($mfx.Contains($boot2)) {
      $mfx = $mfx.Replace($boot2, $boot2 + "`r`n<uses-permission android:name=`"" + $need + "`"/>")
      [System.IO.File]::WriteAllText($mf, $mfx, (New-Object System.Text.UTF8Encoding($false)))
      Write-Host ('manifest: added ' + $need)
    } else {
      Write-Host ('manifest: anchor missing - ' + $need + ' NOT added')
    }
  } else {
    Write-Host ('manifest: ' + $need + ' already present')
  }
}
# 3c2c) 1.3.4-beta.9：回收退役权限。
#       _apkwork 是复用目录（apktool d 只做一次），清单里上一版插进去的 <uses-permission> 不会
#       自己消失。灵动岛下线后 SYSTEM_ALERT_WINDOW、Live Updates 下线后 POST_PROMOTIONS 都已无用，
#       这里幂等删除（第二次跑找不到就什么都不做）。
foreach ($gone in @('android.permission.SYSTEM_ALERT_WINDOW', 'android.permission.POST_PROMOTIONS')) {
  $mfG = Get-Content $mf -Raw -Encoding UTF8
  $patG = '\s*<uses-permission android:name="' + [regex]::Escape($gone) + '"\s*/>'
  if ([regex]::IsMatch($mfG, $patG)) {
    $mfG = [regex]::Replace($mfG, $patG, '')
    [System.IO.File]::WriteAllText($mf, $mfG, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host ('manifest: dropped ' + $gone)
  } else {
    Write-Host ('manifest: ' + $gone + ' already absent')
  }
}
# 3c4) 窗口底色（1.3.3-beta.3）：系统画的启动窗口 / 回前台窗口不要用 Theme.Material 自带的 #303030 灰。
#      三处一起改：manifest 把 QqbotActivity 的 theme 换成 Theme.QqbotPage；values 与 values-night
#      写 windowBackground（深色 #05060A / 浅色 #F3F6FC）；values-v31 与 values-night-v31 再补
#      windowSplashScreenBackground —— Android 12 起冷启动第一帧是系统启动屏，它只认这个属性，
#      不认 windowBackground（实测：只改 windowBackground 时首帧仍是纯白/纯黑）。
#      幂等：风格已存在就跳过，重复执行安全。
$NL = [string][char]13 + [char]10
$themeRef = '@style/Theme.QqbotPage'
$mfTheme = Get-Content $mf -Raw -Encoding UTF8
if ($mfTheme -notmatch [regex]::Escape($themeRef)) {
  $actPat = '(<activity[^>]*android:name="com.termux.app.QqbotActivity"[^>]*?android:theme=")[^"]*(")'
  if ($mfTheme -match $actPat) {
    $mfTheme = [regex]::Replace($mfTheme, $actPat, ('$1' + $themeRef + '$2'))
    [System.IO.File]::WriteAllText($mf, $mfTheme, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host ('res: QqbotActivity theme -> ' + $themeRef)
  } else {
    Write-Host 'res: QqbotActivity tag not found - theme NOT patched'
  }
} else {
  Write-Host 'res: QqbotActivity theme already Theme.QqbotPage'
}
function Ensure-ThemeStyle([string]$Dir, [string]$Color, [bool]$Splash) {
  $file = Join-Path $Dir 'styles.xml'
  if (Test-Path $file) {
    $text = Get-Content $file -Raw -Encoding UTF8
  } else {
    $text = '<?xml version="1.0" encoding="utf-8"?>' + $NL + '<resources>' + $NL + '</resources>' + $NL
  }
  if ($text -match 'name="Theme.QqbotPage"') {
    Write-Host ('res: ' + (Split-Path $Dir -Leaf) + ' Theme.QqbotPage already present')
    return
  }
  $items = '        <item name="android:windowBackground">' + $Color + '</item>' + $NL
  if ($Splash) { $items = $items + '        <item name="android:windowSplashScreenBackground">' + $Color + '</item>' + $NL }
  $style = '    <style name="Theme.QqbotPage" parent="@android:style/Theme.Material.NoActionBar">' + $NL + $items + '    </style>' + $NL
  $text = $text.Replace('</resources>', $style + '</resources>')
  $null = New-Item -ItemType Directory -Force -Path $Dir
  [System.IO.File]::WriteAllText($file, $text, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host ('res: ' + (Split-Path $Dir -Leaf) + ' Theme.QqbotPage written (' + $Color + ')')
}
Ensure-ThemeStyle (Join-Path $ROOT '_apkwork\res\values') '#fff3f6fc' $false
Ensure-ThemeStyle (Join-Path $ROOT '_apkwork\res\values-night') '#ff05060a' $false
Ensure-ThemeStyle (Join-Path $ROOT '_apkwork\res\values-v31') '#fff3f6fc' $true
Ensure-ThemeStyle (Join-Path $ROOT '_apkwork\res\values-night-v31') '#ff05060a' $true

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
  'com.android.alarm.permission.SET_ALARM',
  'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
  'android.permission.WRITE_EXTERNAL_STORAGE'
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
# sharedUserId is intentionally KEPT: removing it changes the app UID and Android then
# refuses the in-place upgrade with INSTALL_FAILED_UID_CHANGED (verified on 1.2.4 build).
# Dropping it has to wait for a release that tells users to uninstall once.
# Do NOT unexport the two Termux content providers: Android throws
# 'Provider must be exported' at startup for providers that declare
# grantUriPermissions=true (verified: app crashed on launch with
# SecurityException Unable to get provider TermuxDocumentsProvider).
if ($xml3 -ne $orig3) {
  [System.IO.File]::WriteAllText($mf, $xml3, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host ("manifest: trimmed {0} dangerous entries, RunCommandService unexported" -f $removed)
} else {
  Write-Host 'manifest: nothing to trim (already clean)'
}
# 3c4) AV heuristics round 2 (1.3.0): the quick-settings tile and the desktop widget are both
#      exported components this app never needs (we drive everything from the WebView), and VIBRATE is
#      referenced by nothing in the sources. Scanner engines score 'resident + auto-start + extra
#      exported components' as the a.gray.devicemaster family, so drop the two components and the
#      unused permission. Idempotent: each replace simply finds nothing on the second run.
$xml4 = Get-Content $mf -Raw -Encoding UTF8
$orig4 = $xml4
$xml4 = [regex]::Replace($xml4, '\s*<uses-permission android:name="android\.permission\.VIBRATE"\s*/>', '')
$xml4 = [regex]::Replace($xml4, '(?s)\s*<receiver android:name="com\.termux\.app\.QqbotWidgetProvider".*?</receiver>', '')
$xml4 = [regex]::Replace($xml4, '(?s)\s*<service android:name="com\.termux\.app\.QqbotTileService".*?</service>', '')
if ($xml4 -ne $orig4) {
  [System.IO.File]::WriteAllText($mf, $xml4, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host 'manifest: dropped widget receiver, tile service, VIBRATE'
} else {
  Write-Host 'manifest: widget/tile already dropped'
}
# 3c5) background-run resilience (1.3.3-beta.6).
#      (a) QqbotGuardService must NOT die with the task: stopWithTask=false is the
#          default for services, but some ROMs honour an explicit flag and it documents
#          the intent next to onTaskRemoved().
#      (b) KeepAliveJobService is the JobScheduler safety net that brings the guard
#          service back when AlarmManager is throttled/killed by the ROM. A JobService
#          MUST declare android.permission.BIND_JOB_SERVICE or the system refuses to run it.
#      Idempotent: each step is skipped when its marker is already present.
$mfJob = Get-Content $mf -Raw -Encoding UTF8
$mfJobOrig = $mfJob
$svcTag = [regex]::Match($mfJob, '<service\s+[^>]*QqbotGuardService[^>]*?/?>')
if ($svcTag.Success) {
  $tag = $svcTag.Value
  $tag2 = $tag
  if ($tag2 -notmatch 'android:stopWithTask') {
    if ($tag2.EndsWith('/>')) { $tag2 = $tag2.Substring(0, $tag2.Length - 2) + ' android:stopWithTask="false"/>' }
    else { $tag2 = $tag2.Substring(0, $tag2.Length - 1) + ' android:stopWithTask="false">' }
  }
  if ($tag2 -ne $tag) { $mfJob = $mfJob.Replace($tag, $tag2); Write-Host 'manifest: QqbotGuardService stopWithTask=false' }
  else { Write-Host 'manifest: QqbotGuardService stopWithTask already set' }
} else {
  Write-Host 'manifest: QqbotGuardService tag not found - stopWithTask NOT patched'
}
if ($mfJob -notmatch 'KeepAliveJobService') {
  $jobSvc = '<service android:exported="false" android:name="com.termux.app.KeepAliveJobService" android:permission="android.permission.BIND_JOB_SERVICE"/>'
  $m2 = [regex]::Match($mfJob, '<service\s+[^>]*QqbotGuardService[^>]*?>')
  if ($m2.Success) {
    $mfJob = $mfJob.Insert($m2.Index + $m2.Length, $NL + '        ' + $jobSvc)
    Write-Host 'manifest: added KeepAliveJobService'
  } else {
    Write-Host 'manifest: anchor for KeepAliveJobService missing - NOT added'
  }
} else {
  Write-Host 'manifest: KeepAliveJobService already present'
}
if ($mfJob -ne $mfJobOrig) {
  [System.IO.File]::WriteAllText($mf, $mfJob, (New-Object System.Text.UTF8Encoding($false)))
}
# 3c5b) 1.3.4-beta.9：前台服务类型（Android 14 起是硬要求）。
#       targetSdk>=34 的机器上，startForeground 前没声明 foregroundServiceType 会直接抛
#       MissingForegroundServiceTypeException 把进程打死；Android 15/16 还会按类型再校验一遍。
#       本服务只做「沙盒保活 + 状态展示」，不碰摄像头 / 麦克风 / 定位，所以用 specialUse，
#       并用 <property> 给出子类型说明（系统「特殊用途」条目里显示的就是这段 value）。
#       幂等：标签上已有该属性就跳过。
$mfFgs = Get-Content $mf -Raw -Encoding UTF8
if ($mfFgs -notmatch 'android:foregroundServiceType') {
  $fgsM = [regex]::Match($mfFgs, '<service\s+[^>]*QqbotGuardService[^>]*?/>')
  if ($fgsM.Success) {
    $fgsTag = $fgsM.Value
    $fgsOpen = $fgsTag.Substring(0, $fgsTag.Length - 2).TrimEnd() + ' android:foregroundServiceType="specialUse">'
    $fgsBody = $NL + '            <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="qqbot-sandbox-keepalive"/>' + $NL + '        </service>'
    $mfFgs = $mfFgs.Replace($fgsTag, $fgsOpen + $fgsBody)
    [System.IO.File]::WriteAllText($mf, $mfFgs, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host 'manifest: QqbotGuardService foregroundServiceType=specialUse'
  } else {
    Write-Host 'manifest: QqbotGuardService tag not found - foregroundServiceType NOT patched'
  }
} else {
  Write-Host 'manifest: foregroundServiceType already present'
}
# 3c6) QqbotBootReceiver: also listen for USER_UNLOCKED (1.3.3-beta.6).
#      Some ROMs drop BOOT_COMPLETED on quick-boot; USER_UNLOCKED arrives after the
#      credential-encrypted storage is unlocked, which is exactly when $PREFIX exists.
#      LOCKED_BOOT_COMPLETED is deliberately NOT used: the app is not directBootAware and
#      all of its state lives in credential-encrypted storage.
$mfBoot = Get-Content $mf -Raw -Encoding UTF8
if ($mfBoot -notmatch 'android\.intent\.action\.USER_UNLOCKED') {
  $bootAct = '<action android:name="android.intent.action.BOOT_COMPLETED"/>'
  if ($mfBoot.Contains($bootAct)) {
    $mfBoot = $mfBoot.Replace($bootAct, $bootAct + $NL + '                <action android:name="android.intent.action.USER_UNLOCKED"/>')
    [System.IO.File]::WriteAllText($mf, $mfBoot, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host 'manifest: QqbotBootReceiver += USER_UNLOCKED'
  } else {
    Write-Host 'manifest: BOOT_COMPLETED action missing - USER_UNLOCKED NOT added'
  }
} else {
  Write-Host 'manifest: USER_UNLOCKED already present'
}
# 3c7) QqbotDailyReport: 每日早报的闹钟接收器（1.3.4-beta.7）。
#      exported=false：只接自己排的 AlarmManager 广播，外部应用不能触发；
#      不写 intent-filter，因为它只靠 PendingIntent 显式唤起。
$mfDaily = Get-Content $mf -Raw -Encoding UTF8
if ($mfDaily -notmatch 'com\.termux\.app\.QqbotDailyReport') {
  $dailyRcv = '<receiver android:exported="false" android:name="com.termux.app.QqbotDailyReport"/>'
  if ($mfDaily.Contains('</application>')) {
    $mfDaily = $mfDaily.Replace('</application>', '        ' + $dailyRcv + $NL + '    </application>')
    [System.IO.File]::WriteAllText($mf, $mfDaily, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host 'manifest: added QqbotDailyReport receiver'
  } else {
    Write-Host 'manifest: </application> missing - QqbotDailyReport NOT added'
  }
} else {
  Write-Host 'manifest: QqbotDailyReport already present'
}
# 3c8) QqbotWeeklyReport: 周报的每周日 20:00 闹钟接收器（1.3.5-beta.1）。
#      与每日早报同一个写法：exported=false、只接自己排的显式 PendingIntent。
$mfWeek = Get-Content $mf -Raw -Encoding UTF8
if ($mfWeek -notmatch 'com\.termux\.app\.QqbotWeeklyReport') {
  $weekRcv = '<receiver android:exported="false" android:name="com.termux.app.QqbotWeeklyReport"/>'
  if ($mfWeek.Contains('</application>')) {
    $mfWeek = $mfWeek.Replace('</application>', '        ' + $weekRcv + $NL + '    </application>')
    [System.IO.File]::WriteAllText($mf, $mfWeek, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host 'manifest: added QqbotWeeklyReport receiver'
  } else {
    Write-Host 'manifest: </application> missing - QqbotWeeklyReport NOT added'
  }
} else {
  Write-Host 'manifest: QqbotWeeklyReport already present'
}
Remove-Item "$ROOT\_apkwork\smali_classes30" -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item "$ROOT\ui\smali" "$ROOT\_apkwork\smali_classes30" -Recurse -Force -ErrorAction Stop
# 1.3.4-beta.7：先清空再整目录复制。只覆盖不删的话，上一版留在
# _apkwork 里的页面（比如已经下线的 plugins.html）会被一直打进包里。
Remove-Item "$ROOT\_apkwork\assets\qqbot" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$ROOT\_apkwork\assets\qqbot" | Out-Null
# 整目录复制：index.html 之外还有 appicon.png 等资源，
# 以前只点名拷两个文件，新增页面会静默漏包（APK 里没有、真机白屏）。
Copy-Item "$ROOT\ui\assets\qqbot\*" "$ROOT\_apkwork\assets\qqbot\" -Recurse -Force -ErrorAction Stop
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
# 1.3.0: the widget + quick-settings tile are disabled on purpose. Both are `exported` components
# that no screen in this app uses, and scanner engines score extra exported components + widgets as
# the a.gray.devicemaster family (user kept hitting the Xiaomi/Tencent warning). Keep the snippet file
# around: re-enabling is one line (drop this block and let 3e run again).
Write-Host '[res] widget receiver + QS tile disabled on purpose (AV heuristics), snippet skipped'

# 4) apktool b
New-Item -ItemType Directory -Force -Path "$ROOT\out" | Out-Null
& "$JDK\java.exe" -jar "$ROOT\_sdk\apktool.jar" b -f -o "$ROOT\out\termux-ui-unsigned.apk" "$ROOT\_apkwork" 2>$null
Write-Host "apktool rc=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { Write-Host 'APKTOOL FAIL'; exit 1 }
Get-Item "$ROOT\out\termux-ui-unsigned.apk" | ForEach-Object { Write-Host ('UI-APK ' + $_.Length) }
Write-Host 'BUILD-UI DONE'
