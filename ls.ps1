$ErrorActionPreference = "Continue"
$res = "C:\Users\zjh19\DSH\qqbot-sandbox\_research"
Get-ChildItem $res | Where-Object { $_.Name -match "deb|tar|xz|control|data" } | ForEach-Object { Write-Host ("  " + $_.Name + "  " + $_.Length) }
Write-Host "--- out dir ---"
Get-ChildItem "C:\Users\zjh19\DSH\qqbot-sandbox\out" | ForEach-Object { Write-Host ("  " + $_.Name + "  " + $_.Length) }
Write-Host "--- tools ---"
Get-ChildItem "C:\Users\zjh19\DSH\qqbot-sandbox\tools" | ForEach-Object { Write-Host ("  " + $_.Name + "  " + $_.Length) }