$java = "C:\Users\zjh19\.dsh-jdks\jdk-21.0.12.1+1\bin\java.exe"
$javac = "C:\Users\zjh19\.dsh-jdks\jdk-21.0.12.1+1\bin\javac.exe"
$tools = "C:\Users\zjh19\DSH\qqbot-sandbox\tools"
$out = "C:\Users\zjh19\DSH\qqbot-sandbox\out\_deb"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $out -Force | Out-Null
& $javac -d $tools (Join-Path $tools "ArTool.java")
if ($LASTEXITCODE -ne 0) { Write-Host "javac FAILED"; exit 1 }
Write-Host "=== QQ_arm64.deb members ==="
& $java -cp $tools ArTool "C:\Users\zjh19\DSH\qqbot-sandbox\_research\QQ_arm64.deb" "control.tar.xz" $out
Write-Host ""
Get-ChildItem $out | ForEach-Object { Write-Host ("  " + $_.Name + "  " + $_.Length) }
$ctl = Join-Path $out "control.tar.xz"
if (Test-Path $ctl) {
  Push-Location $out
  & "C:\Windows\system32\tar.exe" -xf "control.tar.xz"
  Write-Host ("tar exit=" + $LASTEXITCODE)
  Pop-Location
  Get-ChildItem $out | ForEach-Object { Write-Host ("  " + $_.Name + "  " + $_.Length) }
}