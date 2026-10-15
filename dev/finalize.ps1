$ErrorActionPreference = "Continue"
$bash = "C:\Program Files\Git\bin\bash.exe"
$build = "C:\Users\zjh19\DSH\qqbot-sandbox\build"
$fail = 0; $n = 0
Get-ChildItem -Recurse -File -Filter *.sh $build | ForEach-Object {
  $n++
  $out = & $bash -n $_.FullName 2>&1
  if ($LASTEXITCODE -ne 0) { $fail++; Write-Host ("FAIL " + $_.FullName); Write-Host $out }
}
Write-Host ("bash -n : total=" + $n + " failed=" + $fail)

$src = "C:\Users\zjh19\DSH\qqbot-sandbox\out\signed\termux-qqbot-aligned-debugSigned.apk"
$dst = "C:\Users\zjh19\DSH\qqbot-sandbox\QQBot-Sandbox.apk"
Copy-Item $src $dst -Force
Write-Host ("copied -> " + $dst)
Write-Host ("dst bytes : " + (Get-Item $dst).Length)
Write-Host ("dst sha256: " + (Get-FileHash $dst -Algorithm SHA256).Hash.ToLower())
Write-Host ("src sha256: " + (Get-FileHash $src -Algorithm SHA256).Hash.ToLower())