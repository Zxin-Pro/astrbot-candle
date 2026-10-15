$ErrorActionPreference = "Continue"
$res = "C:\Users\zjh19\DSH\qqbot-sandbox\_research"
Get-ChildItem $res | ForEach-Object { Write-Host ("  " + $_.Name + "  " + $_.Length) }
$z = Join-Path $res "NapCat.Shell.zip"
if (Test-Path $z) {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead($z)
  Write-Host ("=== NapCat.Shell.zip entries: " + $zip.Entries.Count + " ===")
  Write-Host "--- config/ 下 ---"
  $zip.Entries | Where-Object { $_.FullName -like "config/*" } | ForEach-Object { Write-Host ("  " + $_.FullName + "  " + $_.Length) }
  Write-Host "--- 顶层（非目录） ---"
  $zip.Entries | Where-Object { $_.FullName -notmatch "/" -and $_.Length -gt 0 } | ForEach-Object { Write-Host ("  " + $_.FullName + "  " + $_.Length) }
  $e = $zip.Entries | Where-Object { $_.FullName -eq "config/onebot11.json" }
  if ($e) {
    $sr = New-Object System.IO.StreamReader($e.Open())
    Write-Host "=== config/onebot11.json 内容 ==="
    Write-Host $sr.ReadToEnd()
    $sr.Close()
  } else { Write-Host "config/onebot11.json 不存在于 zip 中" }
  $zip.Dispose()
} else { Write-Host "NapCat.Shell.zip 不存在" }