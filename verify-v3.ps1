$ErrorActionPreference = "Continue"
$java = "C:\Users\zjh19\.dsh-jdks\jdk-21.0.12.1+1\bin\java.exe"
$tools = "C:\Users\zjh19\DSH\qqbot-sandbox\tools"
$apk = "C:\Users\zjh19\DSH\qqbot-sandbox\out\signed\termux-qqbot-aligned-debugSigned.apk"
$tmp = "C:\Users\zjh19\DSH\qqbot-sandbox\out\_v3"
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead($apk)
Write-Host ("APK entries: " + $z.Entries.Count)
$so = $z.Entries | Where-Object { $_.FullName -eq "lib/arm64-v8a/libtermux-bootstrap.so" }
Write-Host ("bootstrap stored=" + $so.Length + " compressed=" + $so.CompressedLength)
$dexes = @($z.Entries | Where-Object { $_.FullName -match "^classes[0-9]*\.dex$" })
Write-Host ("dex count: " + $dexes.Count)
$c30 = $z.Entries | Where-Object { $_.FullName -eq "classes30.dex" }
if ($c30) { Write-Host ("classes30.dex: " + $c30.Length) } else { Write-Host "classes30.dex MISSING" }
$asset = $z.Entries | Where-Object { $_.FullName -eq "assets/qqbot/index.html" }
if ($asset) { Write-Host ("assets/qqbot/index.html: " + $asset.Length) } else { Write-Host "ASSET MISSING" }
$soPath = Join-Path $tmp "bootstrap.so"
[System.IO.Compression.ZipFileExtensions]::ExtractToFile($so, $soPath, $true)
$z.Dispose()

$pri = "C:\Users\zjh19\DSH\qqbot-sandbox\out\_pristine-bootstrap.so"
$a = [System.IO.File]::ReadAllBytes($soPath)
$b = [System.IO.File]::ReadAllBytes($pri)
$same = $true; for ($i = 0; $i -lt 1328; $i++) { if ($a[$i] -ne $b[$i]) { $same = $false; break } }
Write-Host ("ELF prefix [0,1328) identical: " + $same)
$same2 = $true; for ($i = 29390231; $i -lt 29392992; $i++) { if ($a[$i] -ne $b[$i]) { $same2 = $false; break } }
Write-Host ("ELF suffix identical: " + $same2)

$listPath = Join-Path $tmp "list.txt"
& $java -cp $tools RepackBootstrap list $soPath 1328 29388903 2>$null | Set-Content -Encoding ASCII $listPath
$lines = @(Get-Content $listPath)
Write-Host ("ZIP entries: " + $lines.Count)
Write-Host ("qqbot entries: " + @($lines | Where-Object { $_ -like "opt/qqbot/*" -or $_ -like "etc/profile.d/50-qqbot*" }).Count)
$lines | Where-Object { $_ -like "opt/qqbot/*" } | ForEach-Object { Write-Host ("  " + ($_ -split "\|")[0]) }
Write-Host ("share/LICENSES kept: " + @($lines | Where-Object { $_ -like "share/LICENSES*" }).Count)
Write-Host ("share/man|doc|info leftover: " + @($lines | Where-Object { $_ -like "share/man*" -or $_ -like "share/doc*" -or $_ -like "share/info*" }).Count)

Write-Host ""
Write-Host "==== VerifyBootstrap2 ===="
& $java -cp $tools VerifyBootstrap2 $soPath $pri 1328 29388903 29390231
Write-Host ""
Write-Host ("APK sha256: " + (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower())
Write-Host ("APK bytes : " + (Get-Item $apk).Length)