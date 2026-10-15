$ProgressPreference = "SilentlyContinue"
$pkgs = @("python-ripgrep","faiss-cpu","silk-python","qq-botpy","py-cord","ormsgpack","watchfiles","xinference-client","shipyard-python-sdk","markitdown-no-magika","pydub","rank-bm25","audioop-lts","markitdown")
foreach ($p in $pkgs) {
  $u = "https://pypi.tuna.tsinghua.edu.cn/simple/$p/"
  $h = curl.exe -sL --max-time 40 $u 2>$null
  $txt = ($h | Out-String)
  if ($txt.Length -lt 50) { Write-Host ("  " + $p.PadRight(24) + " FETCH-FAIL"); continue }
  $names = @()
  foreach ($m in [regex]::Matches($txt, 'href="([^"#]+)#sha256=')) { $names += $m.Groups[1].Value }
  $arm = @($names | Where-Object { $_ -match "aarch64|arm64" })
  $anyw = @($names | Where-Object { $_ -match "none-any" })
  $src = @($names | Where-Object { $_ -match "\.tar\.gz$" })
  Write-Host ("  " + $p.PadRight(24) + " total=" + $names.Count + "  aarch64=" + $arm.Count + "  anywheel=" + $anyw.Count + "  sdist=" + $src.Count)
  if ($arm.Count -gt 0) { $arm | Select-Object -Last 2 | ForEach-Object { Write-Host ("        ARM: " + $_) } }
}