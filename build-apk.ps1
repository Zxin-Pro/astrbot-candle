# qqbot-sandbox: rebuild APK (inject scripts into Termux bootstrap -> replace .so -> re-sign)
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI (GBK),
# which corrupts non-ASCII literals and causes parse errors.
$ErrorActionPreference = "Stop"

$ROOT = "C:\Users\zjh19\DSH\qqbot-sandbox"
$JAVA = "C:\Users\zjh19\.dsh-jdks\jdk-21.0.12.1+1\bin\java.exe"
$PREFIX_OFF = 1328
$SECTION = 29388903
$STOCK_APK = Join-Path $ROOT "_research\termux-arm64.apk"
$UI_APK = Join-Path $ROOT "out\termux-ui-unsigned.apk"
# Base APK: the apktool-rebuilt one (with the native UI) when present, else the stock APK.
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
Write-Host "[3/5] pack deploy scripts asset"
$SCRIPTSZIP = Join-Path $OUT "qqbot-scripts.zip"
Remove-Item -Force $SCRIPTSZIP -ErrorAction SilentlyContinue
$sfiles = @()
$sfiles += Get-ChildItem -Recurse -File (Join-Path $BUILD "opt\qqbot")
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
Write-Host ("     {0} files -> {1} ({2} bytes)" -f $sfiles.Count, $SCRIPTSZIP, (Get-Item $SCRIPTSZIP).Length)

# 4) swap the .so inside the APK and add the scripts asset
Write-Host "[4/5] replace bootstrap + add scripts asset"
Remove-Item -Force $UNSIGNED -ErrorAction SilentlyContinue
& $JAVA -cp $TOOLS ApkReplace $APK_SRC $UNSIGNED "lib/arm64-v8a/libtermux-bootstrap.so=$NEW_SO" "assets/qqbot-scripts.zip=$SCRIPTSZIP"
if ($LASTEXITCODE -ne 0) { throw "ApkReplace failed" }

# 5) re-sign
Write-Host "[5/5] sign"
Get-ChildItem -File $SIGNEDDIR | Remove-Item -Force -ErrorAction SilentlyContinue
& $JAVA -jar $SIGNER -a $UNSIGNED -o $SIGNEDDIR
if ($LASTEXITCODE -ne 0) { throw "signing failed" }

Write-Host ""
Write-Host "==== artifacts ===="
Get-ChildItem -File $SIGNEDDIR | ForEach-Object { Write-Host ("  {0}  {1} bytes" -f $_.FullName, $_.Length) }

# 6) copy the signed apk to the repo root under a name that carries the version
#    (release assets are published as AstrBot-<version>.apk so downloads are self-labelling)
$VER = (Get-Content (Join-Path $ROOT "VERSION") -Raw).Trim()
$SIGNED = Join-Path $SIGNEDDIR "termux-qqbot-aligned-debugSigned.apk"
if (Test-Path $SIGNED) {
  $REL = Join-Path $ROOT ("AstrBot-{0}.apk" -f $VER)
  Get-ChildItem -File (Join-Path $ROOT "AstrBot-*.apk") -ErrorAction SilentlyContinue | Remove-Item -Force
  Copy-Item -Force $SIGNED $REL
  Write-Host ("  release copy: {0} ({1} bytes)" -f $REL, (Get-Item $REL).Length)
}