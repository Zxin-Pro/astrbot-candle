$ProgressPreference = "SilentlyContinue"
$dir = "C:\Users\zjh19\DSH\qqbot-sandbox\out\_pkg"
Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $dir -Force | Out-Null
$base = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/dists/noble"
$set = New-Object System.Collections.Generic.HashSet[string]
foreach ($c in @("main","universe")) {
  $gz = Join-Path $dir "$c.gz"
  curl.exe -sL --max-time 300 -o $gz "$base/$c/binary-arm64/Packages.gz"
  Write-Host ("  " + $c + " -> " + (Get-Item $gz).Length + " bytes")
  $in = [System.IO.File]::OpenRead($gz)
  $gz2 = New-Object System.IO.Compression.GZipStream($in, [System.IO.Compression.CompressionMode]::Decompress)
  $sr = New-Object System.IO.StreamReader($gz2)
  while (($line = $sr.ReadLine()) -ne $null) {
    if ($line.StartsWith("Package: ")) { [void]$set.Add($line.Substring(9).Trim()) }
  }
  $sr.Close(); $gz2.Close(); $in.Close()
}
Write-Host ("packages indexed: " + $set.Count)
Write-Host ""
$want = "ca-certificates curl wget unzip zip tar xz-utils procps psmisc jq tzdata locales fontconfig fonts-noto-cjk fonts-wqy-zenhei fonts-dejavu-core python3 python3-venv python3-pip python3-dev build-essential pkg-config libffi-dev libssl-dev libsqlite3-dev xvfb libnss3 libgbm1 libasound2t64 libxss1 libxtst6 libxdamage1 libxrandr2 libxkbcommon0 libxkbcommon-x11-0 libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64 libdrm2 libxcomposite1 libxfixes3 libpango-1.0-0 libcairo2 libgtk-3-0t64 libx11-xcb1 libxcb-dri3-0 libexpat1 libuuid1 libxshmfence1 libnotify4 xdg-utils libatspi2.0-0t64 libsecret-1-0".Split(" ")
$missing = @()
foreach ($p in $want) { if (-not $set.Contains($p)) { $missing += $p } }
Write-Host ("wanted=" + $want.Count + "  MISSING=" + $missing.Count)
foreach ($m in $missing) { Write-Host ("  MISSING -> " + $m) }
Write-Host ""
Write-Host "=== atspi / secret / notify / xdg / t64 candidates ==="
$set | Where-Object { $_ -match "atspi|^libsecret|^libnotify|^xdg-utils|^libgtk-3-0" } | Sort-Object | ForEach-Object { Write-Host ("  " + $_) }