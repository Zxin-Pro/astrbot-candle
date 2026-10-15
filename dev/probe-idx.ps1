$ProgressPreference = "SilentlyContinue"
$urls = @(
  "https://mirrors.tuna.tsinghua.edu.cn/ubuntu/dists/noble/Release",
  "https://mirrors.tuna.tsinghua.edu.cn/ubuntu/dists/noble/main/binary-arm64/Packages.xz",
  "https://mirrors.tuna.tsinghua.edu.cn/ubuntu/dists/noble/main/binary-amd64/Packages.xz",
  "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/dists/noble/Release",
  "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/dists/noble/main/binary-arm64/Packages.xz",
  "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/dists/noble/universe/binary-arm64/Packages.xz",
  "https://mirrors.ustc.edu.cn/ubuntu-ports/dists/noble/Release",
  "https://mirrors.aliyun.com/ubuntu-ports/dists/noble/Release"
)
foreach ($u in $urls) {
  $r = curl.exe -sIL --max-time 25 -o NUL -w "%{http_code}|%{size_download}" $u 2>$null
  Write-Host ("  " + $r + "   " + $u)
}
Write-Host ""
Write-Host "=== does /ubuntu/ noble Release mention arm64? ==="
$rel = "C:\Users\zjh19\DSH\qqbot-sandbox\out\_pkg\rel.txt"
curl.exe -sL --max-time 60 -o $rel "https://mirrors.tuna.tsinghua.edu.cn/ubuntu/dists/noble/Release"
$c = Get-Content $rel
Write-Host ("  lines=" + $c.Count)
$c | Select-String -Pattern "arm64" | Select-Object -First 6 | ForEach-Object { Write-Host ("  " + $_.Line.Trim()) }
$c | Select-String -Pattern "^Architectures" | ForEach-Object { Write-Host ("  " + $_.Line.Trim()) }