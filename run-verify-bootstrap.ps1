$java = "C:\Users\zjh19\.dsh-jdks\jdk-21.0.12.1+1\bin\java.exe"
$javac = "C:\Users\zjh19\.dsh-jdks\jdk-21.0.12.1+1\bin\javac.exe"
$tools = "C:\Users\zjh19\DSH\qqbot-sandbox\tools"
& $javac -d $tools (Join-Path $tools "VerifyBootstrap2.java")
if ($LASTEXITCODE -ne 0) { Write-Host "javac FAILED"; exit 1 }
& $java -cp $tools VerifyBootstrap2 "C:\Users\zjh19\DSH\qqbot-sandbox\out\_chk\modified.so" "C:\Users\zjh19\DSH\qqbot-sandbox\out\_pristine-bootstrap.so" 1328 29388903 29390231