# qqbot-sandbox: rebuild APK (inject scripts into Termux bootstrap -> replace .so -> re-sign)
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI (GBK),
# which corrupts non-ASCII literals and causes parse errors.
#
# Usage:
#   build-apk.ps1                 -> online-deploy APK (downloads at first run)
#   build-apk.ps1 -Arch amd64     -> slim APK for x86_64 (image pulled from the site on first run)
#   build-apk.ps1 -Arch arm64     -> slim APK for arm64  (image pulled from the site on first run)
#   build-apk.ps1 -Arch arm64 -Bundle -> fat APK carrying the 580 MB sandbox image (offline)
#   build-apk.ps1 -AllowStock      -> allow _research\termux-arm64.apk when out\termux-ui-unsigned.apk is missing
param([string]$Arch = "", [switch]$Bundle, [switch]$Update, [switch]$AllowStock)
$ErrorActionPreference = "Stop"

$ROOT = $PSScriptRoot
if (-not $ROOT) { $ROOT = (Get-Location).Path }
# java.exe: $env:JAVA_HOME first, else the JDK next to javac.exe on PATH (JDK 17+ is fine).
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
  $JAVA = Join-Path $env:JAVA_HOME "bin\java.exe"
} else {
  $jc = Get-Command javac.exe -ErrorAction SilentlyContinue
  if ($jc) { $JAVA = Join-Path (Split-Path $jc.Source -Parent) "java.exe" }
}
if (-not $JAVA -or -not (Test-Path $JAVA)) { throw "java.exe not found: set JAVA_HOME or put javac.exe on PATH" }
$JAVAC = Join-Path (Split-Path $JAVA -Parent) "javac.exe"
Write-Host ("java: " + $JAVA)

# helper tools (tools\*.java -> tools\*.class) must be compiled for THIS runtime:
# they are gitignored, and classes built by a newer JDK fail with UnsupportedClassVersionError.
$TOOLSRC = @(Get-ChildItem "$ROOT\tools" -Filter *.java | ForEach-Object { $_.FullName })
$needTools = $false
foreach ($f in $TOOLSRC) {
  $cls = Join-Path "$ROOT\tools" ((Split-Path $f -Leaf) -replace '\.java$', '.class')
  if (-not (Test-Path $cls)) { $needTools = $true }
  elseif ((Get-Item $f).LastWriteTime -gt (Get-Item $cls).LastWriteTime) { $needTools = $true }
}
if ($needTools -and $TOOLSRC.Count -gt 0) {
  Write-Host ("tools: compiling " + $TOOLSRC.Count + " sources")
  & $JAVAC -encoding UTF-8 -nowarn -cp "$ROOT\_sdk\apktool.jar" -d "$ROOT\tools" $TOOLSRC
  if ($LASTEXITCODE -ne 0) { throw "tools compile failed" }
} else { Write-Host "tools: up to date" }
$PREFIX_OFF = 1328
$SECTION = 29388903
$STOCK_APK = Join-Path $ROOT "_research\termux-arm64.apk"
$UI_APK = Join-Path $ROOT "out\termux-ui-unsigned.apk"
# Base APK: the apktool-rebuilt one (with the native UI). A silent fallback to the stock
# APK would let an old UI build ship under the new VERSION, so require an explicit switch.
if (-not (Test-Path $UI_APK) -and -not $AllowStock) {
  throw "out\termux-ui-unsigned.apk not found - run build-ui.ps1 first (or pass -AllowStock to build from _research\termux-arm64.apk)"
}
if (Test-Path $UI_APK) { $APK_SRC = $UI_APK } else { $APK_SRC = $STOCK_APK }
$SIGNER = Join-Path $ROOT "_research\uber-apk-signer.jar"
$TOOLS = Join-Path $ROOT "tools"
$BUILD = Join-Path $ROOT "build"
$OUT = Join-Path $ROOT "out"
$PRISTINE = Join-Path $OUT "_pristine-bootstrap.so"
$NEW_SO = Join-Path $OUT "libtermux-bootstrap.so"
$UNSIGNED = Join-Path $OUT "termux-qqbot-unsigned.apk"
$SIGNEDDIR = Join-Path $OUT "signed"

New-Item -ItemType Directory -Force -Path $OUT, $SIGNEDDIR | Out-Null

# 1) extract the pristine bootstrap .so from the stock APK
Write-Host "[1/5] extract pristine libtermux-bootstrap.so"
Write-Host ("     base apk: {0}" -f $APK_SRC)
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
# Always take the pristine bootstrap from the STOCK apk so the injection math stays valid.
$zip = [System.IO.Compression.ZipFile]::OpenRead($STOCK_APK)
$entry = $zip.Entries | Where-Object { $_.FullName -eq "lib/arm64-v8a/libtermux-bootstrap.so" }
if (-not $entry) { $zip.Dispose(); throw "libtermux-bootstrap.so not found in APK" }
[System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $PRISTINE, $true)
$zip.Dispose()
Write-Host ("     -> {0} ({1} bytes)" -f $PRISTINE, (Get-Item $PRISTINE).Length)

# 2) repack bootstrap: inject every file under build/ as a zip entry
Write-Host "[2/5] inject qqbot scripts"
$jargs = New-Object System.Collections.Generic.List[string]
$jargs.Add("-cp"); $jargs.Add($TOOLS)
$jargs.Add("RepackBootstrap"); $jargs.Add("repack")
$jargs.Add($PRISTINE); $jargs.Add("$PREFIX_OFF"); $jargs.Add("$SECTION"); $jargs.Add($NEW_SO)
# Free room: the stock zip fills the whole 29388903-byte section exactly, so any
# addition needs dropped entries. Everything dropped below is documentation,
# developer-only metadata, or a helper binary that the deploy flow never runs.
# Safety net: TermuxInstaller.java pre-creates the parent directory of every
# SYMLINKS.txt entry (ensureDirectoryExists, line 174) before it creates the links
# (line 209), so dropping a directory that SYMLINKS.txt points into can never break
# extraction; dangling link targets are legal on Android and were already shipped in
# the previous build (share/man|info|doc).
$dropPrefixes = @(
  "share/man",                 # man pages
  "share/info",                # GNU info pages
  "share/doc",                 # package documentation
  "include/",                  # C headers (nothing is ever compiled on device)
  "libexec/installed-tests/",  # termux-exec / termux-core test binaries
  "libexec/termux-am/",        # am.apk; only reached through bin/am, which we never call
  "libexec/termux/",           # command-not-found; bash.bashrc guards it with [ -x ]
  "libexec/telnetd", "libexec/syslogd", "libexec/inetd", "libexec/tftpd",
  "libexec/rmt",               # GNU tar remote-tape helper
  "libexec/frcode",            # findutils locate helper
  "share/aclocal", "share/nano", "share/bash-completion", "share/readline",
  "share/libgpg-error", "share/awk", "share/examples", "share/zsh",
  "share/tabset", "share/pacman",
  "lib/bash/",                 # bash loadable builtins (only via "enable -f")
  "lib/gawk/",                 # gawk extensions (only via "@load")
  "lib/pkgconfig/", "lib/cmake/"   # build-system metadata
)
foreach ($p in $dropPrefixes) { $jargs.Add("--drop"); $jargs.Add($p) }
# NOTE: share/LICENSES, share/terminfo, share/termux-keyring, var/lib/dpkg/**,
# lib/**/*.so, bin/**, etc/** and SYMLINKS.txt are deliberately KEPT.
# A previous -Arch build may have left BUNDLE_ARCH/BUNDLE_SIZE in build/. Clear them before
# build/ is packed into the bootstrap .so, or stale architecture markers get baked in.
Remove-Item -Force (Join-Path $BUILD "opt\qqbot\BUNDLE_ARCH") -ErrorAction SilentlyContinue
Remove-Item -Force (Join-Path $BUILD "opt\qqbot\BUNDLE_SIZE") -ErrorAction SilentlyContinue
$n = 0
Get-ChildItem -Recurse -File $BUILD | Sort-Object FullName | ForEach-Object {
  $rel = $_.FullName.Substring($BUILD.Length + 1).Replace("\", "/")
  $jargs.Add("--add")
  $jargs.Add("$rel=$($_.FullName)")
  $n++
}
Write-Host ("     injecting {0} files" -f $n)
Remove-Item -Force $NEW_SO -ErrorAction SilentlyContinue
& $JAVA @jargs
if ($LASTEXITCODE -ne 0) { throw "RepackBootstrap failed" }

# 3) pack the deploy scripts into assets/qqbot-scripts.zip
#
# Why: Termux only unpacks the bootstrap (including opt/qqbot) when $PREFIX is missing or
# empty, so an over-the-top APK update leaves the OLD scripts on the device and new script
# features (timezone sync, watchdog, per-instance supervisor) never reach the user.
# QqbotActivity.syncScripts() unpacks this asset over $PREFIX whenever the APK lastUpdateTime
# changes. Keep it small: a zip of build/opt/qqbot + build/etc/profile.d/50-qqbot-sandbox.sh.
# 2b) stamp the sandbox-side version file. The repo-root VERSION only names the APK; the
#     scripts read $PREFIX/opt/qqbot/VERSION (crash aggregation, doctor), so it has to ship.
$VERF = Join-Path $BUILD "opt\qqbot\VERSION"
$rootVer = (Get-Content (Join-Path $ROOT "VERSION") -Raw).Trim()
[System.IO.File]::WriteAllText($VERF, $rootVer + "`n")
Write-Host ("     version file: {0}" -f $rootVer)

Write-Host "[3/5] pack deploy scripts asset"
# BUNDLE_ARCH/BUNDLE_SIZE were already cleared before build/ was injected into the .so.
$SCRIPTSZIP = Join-Path $OUT "qqbot-scripts.zip"
Remove-Item -Force $SCRIPTSZIP -ErrorAction SilentlyContinue
$sfiles = @()
$sfiles += Get-ChildItem -Recurse -File -Force (Join-Path $BUILD "opt\qqbot")
$prof = Join-Path $BUILD "etc\profile.d\50-qqbot-sandbox.sh"
if (Test-Path $prof) { $sfiles += (Get-Item $prof) }
$zout = [System.IO.Compression.ZipFile]::Open($SCRIPTSZIP, [System.IO.Compression.ZipArchiveMode]::Create)
foreach ($f in $sfiles) {
  $rel = $f.FullName.Substring($BUILD.Length + 1).Replace("\", "/")
  $ze = $zout.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
  $zs = $ze.Open()
  $zb = [System.IO.File]::ReadAllBytes($f.FullName)
  $zs.Write($zb, 0, $zb.Length)
  $zs.Dispose()
}
$zout.Dispose()
# The stamped VERSION file must not linger in build/ (it is generated, not source).
Remove-Item -Force $VERF -ErrorAction SilentlyContinue
Write-Host ("     {0} files -> {1} ({2} bytes)" -f $sfiles.Count, $SCRIPTSZIP, (Get-Item $SCRIPTSZIP).Length)

# 3a) error report token
# The build machine keeps the fine-grained GitHub token of Zxin-Pro/astrbot-errlogs in
# _research\errdump-token.txt (gitignored: never in git, never in the sources). Copy it
# verbatim into the scripts asset (no BOM, no extra newline) so upload-log.sh can read it
# on the device as opt/qqbot/errdump-token.txt. Without the file the APK simply cannot
# report anything, so the build only warns and goes on.
$TOKF = Join-Path $ROOT "_research\errdump-token.txt"
$TOKOK = (Test-Path -LiteralPath $TOKF) -and ((Get-Item -LiteralPath $TOKF).Length -gt 20)
if ($TOKOK) {
  $tokBytes = [System.IO.File]::ReadAllBytes($TOKF)
  $tokOff = 0
  if (($tokBytes.Length -ge 3) -and ($tokBytes[0] -eq 0xEF) -and ($tokBytes[1] -eq 0xBB) -and ($tokBytes[2] -eq 0xBF)) {
    $tokOff = 3
  }
  $tokLen = $tokBytes.Length - $tokOff
  $zout = [System.IO.Compression.ZipFile]::Open($SCRIPTSZIP, [System.IO.Compression.ZipArchiveMode]::Update)
  $oldsz = $zout.GetEntry("opt/qqbot/errdump-token.txt")
  if ($oldsz) { $oldsz.Delete() }
  $zesz = $zout.CreateEntry("opt/qqbot/errdump-token.txt", [System.IO.Compression.CompressionLevel]::Optimal)
  $zssz = $zesz.Open()
  $zssz.Write($tokBytes, $tokOff, $tokLen)
  $zssz.Dispose()
  $zout.Dispose()
  $zout = $null
  Write-Host ("  token: injected errdump-token.txt ({0} bytes)" -f $tokLen)
} else {
  Write-Host "  token: _research/errdump-token.txt missing, this APK cannot send error reports"
}

# 3b) bundled offline image
# When -Arch is given, ship the fully installed sandbox as assets/qqbot-bundle.tar.xz so a
# first run needs no network at all: step 2 extracts it and steps 3-6 are already "done".
# The size hint goes into the scripts (opt/qqbot/BUNDLE_ARCH) so a wrong-arch package is
# rejected instead of unpacked (an amd64 tree on arm64 fails every later step with exec errors).
$ARCHF = Join-Path $BUILD "opt\qqbot\BUNDLE_ARCH"
$SIZEF = Join-Path $BUILD "opt\qqbot\BUNDLE_SIZE"
$BUNDLE_PAYLOAD = ""
if ($Arch -ne "" -and $Arch -ne "amd64" -and $Arch -ne "arm64") { throw "-Arch must be amd64 or arm64" }
# try/finally: markers are build inputs only, never source files. Remove them even when
# the bundle branch throws, so the next run cannot pick up a stale architecture marker.
try {
  if ($Arch -ne "" -and $Bundle) {
    $BUNDLE_PAYLOAD = Join-Path $OUT ("bundle-" + $Arch + ".tar.xz")
    if (-not (Test-Path $BUNDLE_PAYLOAD)) { throw ("bundle payload not found: " + $BUNDLE_PAYLOAD) }
    [System.IO.File]::WriteAllText($ARCHF, $Arch + "`n")
    # Re-pack the scripts asset so it carries BUNDLE_ARCH (it was built a moment ago).
    $zout = [System.IO.Compression.ZipFile]::Open($SCRIPTSZIP, [System.IO.Compression.ZipArchiveMode]::Update)
    $ze = $zout.CreateEntry("opt/qqbot/BUNDLE_ARCH", [System.IO.Compression.CompressionLevel]::Optimal)
    $zs = $ze.Open()
    $zb = [System.Text.Encoding]::ASCII.GetBytes($Arch + "`n")
    $zs.Write($zb, 0, $zb.Length)
    $zs.Dispose()
    # BUNDLE_SIZE lets the UI show "x MB / y MB" without opening the 600 MB asset.
    $ze2 = $zout.CreateEntry("opt/qqbot/BUNDLE_SIZE", [System.IO.Compression.CompressionLevel]::Optimal)
    $zs2 = $ze2.Open()
    $zb2 = [System.Text.Encoding]::ASCII.GetBytes([string](Get-Item $BUNDLE_PAYLOAD).Length + "`n")
    $zs2.Write($zb2, 0, $zb2.Length)
    $zs2.Dispose()
    $zout.Dispose()
    Write-Host ("     bundle: {0} ({1} bytes) arch={2}" -f $BUNDLE_PAYLOAD, (Get-Item $BUNDLE_PAYLOAD).Length, $Arch)
  }
} finally {
  # Never leave per-build markers in build/ for the next run.
  Remove-Item -Force $ARCHF -ErrorAction SilentlyContinue
  Remove-Item -Force $SIZEF -ErrorAction SilentlyContinue
}

# 3c) relocate the bootstrap prefix. The Termux userland hardcodes /data/data/com.termux
#     (RUNPATH of ~250 ELF binaries, shebangs, SYMLINKS.txt). Rewrite it for the
#     zhuxin.astrbot package id before the .so is packed into the APK.
Write-Host "[3c/5] relocate bootstrap prefix -> /data/data/zhuxin.astrbot"
$SO_RELOC = Join-Path $OUT "libtermux-bootstrap-reloc.so"
& python (Join-Path $ROOT "tools\reloc_bootstrap.py") $NEW_SO $SO_RELOC "zhuxin.astrbot" $PRISTINE
if ($LASTEXITCODE -ne 0) { throw "bootstrap relocation failed" }
Write-Host ("     -> {0} ({1} bytes)" -f $SO_RELOC, (Get-Item $SO_RELOC).Length)

# 4) swap the .so inside the APK and add the scripts asset
Write-Host "[4/5] replace bootstrap + add scripts asset"
Remove-Item -Force $UNSIGNED -ErrorAction SilentlyContinue
$REPL = @("lib/arm64-v8a/libtermux-bootstrap.so=$SO_RELOC", "assets/qqbot-scripts.zip=$SCRIPTSZIP")
if ($BUNDLE_PAYLOAD -ne "") { $REPL += ("assets/qqbot-bundle.tar.xz=" + $BUNDLE_PAYLOAD) }
& $JAVA -Xmx4g -cp $TOOLS ApkReplace2 $APK_SRC $UNSIGNED @REPL
if ($LASTEXITCODE -ne 0) { throw "ApkReplace2 failed" }

# 4b) rename the manifest package. apktool keeps the decoded manifest package="com.termux"
#     so the generated R classes still match the smali; the binary AXML string pool is
#     rewritten here instead (package / sharedUserId / authorities / permission names).
Write-Host "[4b/5] patch AndroidManifest package -> zhuxin.astrbot"
& python (Join-Path $ROOT "tools\patch_manifest.py") $UNSIGNED
if ($LASTEXITCODE -ne 0) { throw "manifest package patch failed" }

# 5) re-sign
Write-Host "[5/5] sign"
Get-ChildItem -File $SIGNEDDIR | Remove-Item -Force -ErrorAction SilentlyContinue
$KS = Join-Path $ROOT "_research\debug.keystore"
if (Test-Path $KS) { & $JAVA -jar $SIGNER -a $UNSIGNED -o $SIGNEDDIR --ksDebug $KS }
else { Write-Host "WARN: _research\debug.keystore missing - signing with the signer built-in key"; & $JAVA -jar $SIGNER -a $UNSIGNED -o $SIGNEDDIR }
if ($LASTEXITCODE -ne 0) { throw "signing failed" }

Write-Host ""
Write-Host "==== artifacts ===="
Get-ChildItem -File $SIGNEDDIR | ForEach-Object { Write-Host ("  {0}  {1} bytes" -f $_.FullName, $_.Length) }

# 6) copy the signed apk to the repo root under a name that carries the version
#    (release assets are published as AstrBot-<version>.apk so downloads are self-labelling)
$VER = (Get-Content (Join-Path $ROOT "VERSION") -Raw).Trim()
$SIGNED = Join-Path $SIGNEDDIR "termux-qqbot-aligned-debugSigned.apk"
# Guard release naming: the signed APK is built from out\termux-ui-unsigned.apk, but its
# version comes from _apkwork\apktool.yml. A stale UI build must never be published under
# the new VERSION, so compare both values before generating the release filename.
$verMatch = [regex]::Match($VER, '^(\d+)\.(\d+)\.(\d+)$')
if (-not $verMatch.Success) { throw ("VERSION is not MAJOR.MINOR.PATCH: " + $VER) }
$EXPECT_CODE = [int]$verMatch.Groups[1].Value * 10000 + [int]$verMatch.Groups[2].Value * 100 + [int]$verMatch.Groups[3].Value
$APKTOOL_YML = Join-Path $ROOT "_apkwork\apktool.yml"
if (-not (Test-Path -LiteralPath $APKTOOL_YML)) {
  throw ("_apkwork\apktool.yml not found - run build-ui.ps1 first to rebuild out\termux-ui-unsigned.apk for " + $VER)
}
$apktoolYml = Get-Content -LiteralPath $APKTOOL_YML -Raw
if (-not $apktoolYml) { throw "_apkwork\apktool.yml is empty - run build-ui.ps1 first" }
$mCode = [regex]::Match($apktoolYml, '(?m)^\s*versionCode:\s*([^\r\n]+?)\s*$')
$mName = [regex]::Match($apktoolYml, '(?m)^\s*versionName:\s*([^\r\n]+?)\s*$')
if (-not $mCode.Success -or -not $mName.Success) {
  throw "_apkwork\apktool.yml has no versionCode/versionName - run build-ui.ps1 first"
}
$apkCode = [int]($mCode.Groups[1].Value.Trim().Trim([char]0x27).Trim([char]0x22))
$apkName = $mName.Groups[1].Value.Trim().Trim([char]0x27).Trim([char]0x22)
if ($apkCode -ne $EXPECT_CODE -or $apkName -ne $VER) {
  throw ("APK version mismatch: _apkwork\apktool.yml has versionCode={0} versionName={1}, but VERSION={2} expects versionCode={3}. Run build-ui.ps1 first to rebuild out\termux-ui-unsigned.apk." -f $apkCode, $apkName, $VER, $EXPECT_CODE)
}
if (Test-Path $SIGNED) {
  # Only the file this run is about to write may be removed: with -Arch there are two
  # builds of the same version sitting in the repo root and a blanket wildcard delete
  # would silently eat the other architecture's APK.
  $SUF = ""
  if ($Arch -ne "") { $SUF = "-" + $Arch }
  $REL = Join-Path $ROOT ("AstrBot-{0}{1}.apk" -f $VER, $SUF)
  if (Test-Path $REL) { Remove-Item -Force $REL }
  Copy-Item -Force $SIGNED $REL
  Write-Host ("  release copy: {0} ({1} bytes)" -f $REL, (Get-Item $REL).Length)

  # 6b) copy the signed APK to the desktop on every build, replacing any previous copy:
  #     the desktop should always hold exactly the latest installer.
  $DESK = [Environment]::GetFolderPath('Desktop')
  if (-not $DESK -and $env:USERPROFILE) { $DESK = Join-Path $env:USERPROFILE "Desktop" }
  if ($DESK -and (Test-Path -LiteralPath $DESK -PathType Container)) {
    $DESKAPK = Join-Path $DESK ([System.IO.Path]::GetFileName($REL))
    $DESKTMP = $DESKAPK + ".tmp"
    Remove-Item -Force $DESKTMP -ErrorAction SilentlyContinue
    Copy-Item -Force $REL $DESKTMP
    Move-Item -Force $DESKTMP $DESKAPK
    # Only after the new copy is in place remove older installers.
    Get-ChildItem -LiteralPath $DESK -File -Force -Filter "AstrBot-*.apk" -ErrorAction SilentlyContinue |
      Where-Object { $_.FullName -ne $DESKAPK } |
      Remove-Item -Force -ErrorAction SilentlyContinue
    Write-Host ("  desktop copy: {0} ({1} bytes)" -f $DESKAPK, (Get-Item $DESKAPK).Length)
  } else {
    Write-Host "  WARN: desktop folder not found - skipped desktop copy"
  }
}