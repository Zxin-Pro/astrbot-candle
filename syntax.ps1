$ErrorActionPreference = "Continue"
$bash = "C:\Program Files\Git\bin\bash.exe"
$root = "C:\Users\zjh19\DSH\qqbot-sandbox\build"
$fail = 0
Get-ChildItem -Path $root -Recurse -Filter *.sh | ForEach-Object {
  $p = $_.FullName
  $o = & $bash -n $p 2>&1
  if ($LASTEXITCODE -ne 0) { $fail++; Write-Host ("FAIL " + $p); Write-Host ($o | Out-String) }
}
Write-Host ("bash -n failures: " + $fail)