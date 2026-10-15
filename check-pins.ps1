$ProgressPreference = "SilentlyContinue"
function Show($pkg, $pat) {
  $u = "https://pypi.tuna.tsinghua.edu.cn/simple/$pkg/"
  $h = curl.exe -sL --max-time 40 $u 2>$null
  $txt = ($h | Out-String)
  $names = @()
  foreach ($m in [regex]::Matches($txt, 'href="([^"#]+)#sha256=')) { $names += $m.Groups[1].Value }
  $hit = @($names | Where-Object { $_ -match $pat })
  Write-Host ("=== " + $pkg + "  matching /" + $pat + "/ -> " + $hit.Count)
  $hit | ForEach-Object { Write-Host ("    " + $_) }
}
Show "python-ripgrep" "0\.0\.8-"
Show "faiss-cpu" "faiss_cpu-1\.1[0-9]"