$ProgressPreference = "SilentlyContinue"
function Names($pkg) {
  $u = "https://pypi.tuna.tsinghua.edu.cn/simple/$pkg/"
  $h = curl.exe -sL --max-time 60 $u 2>$null
  $txt = ($h | Out-String)
  $n = @()
  foreach ($m in [regex]::Matches($txt, 'href="([^"#]+)#sha256=')) { $n += $m.Groups[1].Value }
  return $n
}
$f = Names "faiss-cpu"
Write-Host ("faiss-cpu total=" + $f.Count)
$f | Where-Object { $_ -match "faiss_cpu-1\.1[23]" -and $_ -match "aarch64" } | ForEach-Object { Write-Host ("  " + $_) }
Write-Host ""
$s = Names "silk-python"
Write-Host ("silk-python total=" + $s.Count)
$s | Where-Object { $_ -match "silk_python-0\.2\.[678]" -and $_ -match "aarch64" } | ForEach-Object { Write-Host ("  " + $_) }
Write-Host ""
$w = Names "watchfiles"
$w | Where-Object { $_ -match "watchfiles-1\.[0-9]+\.[0-9]+-cp312" -and $_ -match "aarch64" } | Select-Object -Last 3 | ForEach-Object { Write-Host ("  WF " + $_) }
$o = Names "ormsgpack"
$o | Where-Object { $_ -match "cp312" -and $_ -match "aarch64" } | Select-Object -Last 3 | ForEach-Object { Write-Host ("  OM " + $_) }