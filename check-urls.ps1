$ProgressPreference = "SilentlyContinue"
$urls = @(
  "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/dists/stable/Release",
  "https://packages-cf.termux.dev/apt/termux-main/dists/stable/Release",
  "https://mirrors.tuna.tsinghua.edu.cn/ubuntu/dists/noble/Release",
  "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
  "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
  "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
  "https://pypi.tuna.tsinghua.edu.cn/simple/",
  "https://qqdl.gtimg.cn/qqfile/QQNT/9.9.36/beta/9ee04bef/linuxqq_3.2.34-53644_arm64.deb",
  "https://ghfast.top/https://github.com/NapNeko/NapCatQQ/releases/download/v4.18.38/NapCat.Shell.zip",
  "https://ghfast.top/https://github.com/AstrBotDevs/AstrBot/archive/refs/tags/v4.28.2.tar.gz",
  "https://ghfast.top/https://github.com/AstrBotDevs/AstrBot/releases/download/v4.28.2/AstrBot-v4.28.2-dashboard.zip"
)
foreach ($u in $urls) {
  $code = curl.exe -sIL --max-time 20 -o NUL -w "%{http_code}" $u 2>$null
  $len  = curl.exe -sIL --max-time 20 -o NUL -w "%{size_download}|%{header_json}" $u 2>$null
  Write-Host ("  " + $code + "  " + $u)
}
Write-Host ""
Write-Host "=== github mirror prefixes against NapCat.Shell.zip ==="
$mirrors = @("https://ghfast.top/","https://gh-proxy.com/","https://ghproxy.net/","https://ghproxy.cc/","https://gh.llkk.cc/","https://gh.xxooo.cf/","https://github.moeyy.xyz/","https://hub.gitmirror.com/")
foreach ($m in $mirrors) {
  $u = $m + "https://github.com/NapNeko/NapCatQQ/releases/download/v4.18.38/NapCat.Shell.zip"
  $code = curl.exe -sIL --max-time 15 -o NUL -w "%{http_code}" $u 2>$null
  Write-Host ("  " + $code + "  " + $m)
}