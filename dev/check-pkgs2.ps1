$ErrorActionPreference = "Continue"
Add-Type -AssemblyName System.IO.Compression
$tmp = "C:\Users\zjh19\DSH\qqbot-sandbox\out\_pkg"
New-Item -ItemType Directory -Path $tmp -Force | Out-Null

# ---- 1) Termux apt repo: does it provide the packages 10-termux.sh installs? ----
$url = "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz"
$gz = Join-Path $tmp "termux-Packages.gz"
if (-not (Test-Path $gz)) {
  & curl.exe -sL --max-time 60 -o $gz $url
}
Write-Host ("termux Packages.gz bytes = " + (Get-Item $gz).Length)
$fs = [System.IO.File]::OpenRead($gz)
$gzs = New-Object System.IO.Compression.GZipStream($fs, [System.IO.Compression.CompressionMode]::Decompress)
$sr = New-Object System.IO.StreamReader($gzs)
$text = $sr.ReadToEnd()
$sr.Close()
$names = New-Object System.Collections.Generic.HashSet[string]
foreach ($line in ($text -split "`n")) {
  if ($line.StartsWith("Package: ")) { [void]$names.Add($line.Substring(9).Trim()) }
}
Write-Host ("termux packages indexed = " + $names.Count)
$want = @("proot","tar","xz-utils","curl","unzip","coreutils","findutils","ncurses-utils")
foreach ($w in $want) {
  Write-Host ("  " + $w.PadRight(16) + " -> " + ($(if ($names.Contains($w)) { "OK" } else { "MISSING" })))
}

# ---- 2) ubuntu-base rootfs: does it contain the tools the deploy chain calls? ----
$rootfsUrl = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
$rfs = Join-Path $tmp "ubuntu-base.tar.gz"
if (-not (Test-Path $rfs)) {
  Write-Host "downloading ubuntu-base (this may take a while)..."
  & curl.exe -sL --max-time 600 -o $rfs $rootfsUrl
}
Write-Host ("ubuntu-base bytes = " + (Get-Item $rfs).Length)
$listFile = Join-Path $tmp "rootfs-list.txt"
& "C:\Windows\system32\tar.exe" -tzf $rfs | Set-Content -Encoding ASCII $listFile
$rl = @(Get-Content $listFile)
Write-Host ("rootfs entries = " + $rl.Count)
$need = @("./bin/bash","./usr/bin/dpkg-deb","./usr/bin/apt-get","./usr/bin/tar","./usr/bin/gzip","./usr/bin/xz","./usr/bin/env","./usr/bin/find","./usr/bin/sed","./usr/bin/awk","./etc/passwd","./usr/bin/apt","./usr/bin/dpkg")
foreach ($n in $need) {
  $hit = $rl -contains $n
  Write-Host ("  " + $n.PadRight(24) + " -> " + ($(if ($hit) { "OK" } else { "MISSING" })))
}
$py = @($rl | Where-Object { $_ -like "./usr/bin/python3*" })
Write-Host ("  python3 in base image: " + $(if ($py.Count -gt 0) { "yes (" + $py[0] + ")" } else { "no (installed later via apt)" }))
$xv = @($rl | Where-Object { $_ -like "*xvfb*" })
Write-Host ("  xvfb in base image   : " + $(if ($xv.Count -gt 0) { "yes" } else { "no (installed later via apt)" }))