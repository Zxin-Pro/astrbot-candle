$ProgressPreference = "SilentlyContinue"
$suites = @("noble","noble-updates","noble-backports","noble-security")
$bases = @(
  "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports",
  "https://mirrors.ustc.edu.cn/ubuntu-ports",
  "https://mirrors.aliyun.com/ubuntu-ports",
  "https://ports.ubuntu.com/ubuntu-ports",
  "https://mirrors.tuna.tsinghua.edu.cn/ubuntu"
)
foreach ($b in $bases) {
  Write-Host ("=== " + $b + " ===")
  foreach ($s in $suites) {
    $code = curl.exe -sIL --max-time 25 -o NUL -w "%{http_code}" "$b/dists/$s/Release" 2>$null
    Write-Host ("  " + $code + "  " + $s)
  }
  foreach ($c in @("main","universe","restricted","multiverse")) {
    $code = curl.exe -sIL --max-time 25 -o NUL -w "%{http_code}" "$b/dists/noble/$c/binary-arm64/Packages.xz" 2>$null
    Write-Host ("  " + $code + "  noble/" + $c + "/binary-arm64/Packages.xz")
  }
}