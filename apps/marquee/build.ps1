# Manual APK build for Marquee (no Gradle): aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Continue (not Stop): native tools like keytool/apksigner write status to stderr, which
# ErrorActionPreference=Stop would treat as fatal. We gate on $LASTEXITCODE instead.
$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$sdk = "$root\tools\android-sdk"
$jdk = (Get-ChildItem "$root\tools" -Directory -Filter 'jdk-17*' | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdk                       # d8.bat / apksigner.bat resolve java through this
$env:PATH = "$jdk\bin;$env:PATH"
$bt  = "$sdk\build-tools\34.0.0"
$androidJar = "$sdk\platforms\android-30\android.jar"
$aapt2 = "$bt\aapt2.exe"
$srcMain = "$root\app\src\main"
$out = "$root\build"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null

Write-Host "[1/8] generating art"
& "$root\make-art.ps1"

Write-Host "[2/8] aapt2 compile resources"
$compiled = "$out\res.zip"
& $aapt2 compile --dir "$srcMain\res" -o $compiled

Write-Host "[3/8] aapt2 link -> base.apk + R.java"
$gen = "$out\gen"; New-Item -ItemType Directory -Force $gen | Out-Null
# NOTE: assets are added later with forced forward-slash names — aapt2 -A stores
# them with a Windows backslash separator, which breaks file:///android_asset lookups.
& $aapt2 link -o "$out\base.apk" -I $androidJar `
  --manifest "$srcMain\AndroidManifest.xml" `
  --java $gen `
  --min-sdk-version 21 --target-sdk-version 30 --version-code 1 --version-name 1.0 `
  $compiled
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[4/8] javac"
$classes = "$out\classes"; New-Item -ItemType Directory -Force $classes | Out-Null
$javaFiles = @()
$javaFiles += (Get-ChildItem "$srcMain\java" -Recurse -Filter *.java).FullName
$javaFiles += (Get-ChildItem $gen -Recurse -Filter R.java).FullName
& "$jdk\bin\javac.exe" -source 8 -target 8 -encoding UTF-8 -classpath $androidJar -d $classes @javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[5/8] d8 -> classes.dex"
$classFiles = (Get-ChildItem $classes -Recurse -Filter *.class).FullName
& "$bt\d8.bat" --min-api 21 --lib $androidJar --output $out @classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[6/8] package dex + assets into apk (forward-slash entry names)"
Copy-Item "$out\base.apk" "$out\marquee-unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\marquee-unsigned.apk", 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\classes.dex", "classes.dex") | Out-Null
$assetsRoot = "$srcMain\assets"
foreach ($f in Get-ChildItem $assetsRoot -Recurse -File) {
  $rel = $f.FullName.Substring($assetsRoot.Length + 1).Replace('\', '/')
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, "assets/$rel") | Out-Null
}
$zip.Dispose()

Write-Host "[7/8] zipalign"
& "$bt\zipalign.exe" -f 4 "$out\marquee-unsigned.apk" "$out\marquee-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[8/8] sign"
$ks = "$root\marquee.keystore"
if (-not (Test-Path $ks)) {
  & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -alias marquee -keyalg RSA -keysize 2048 `
    -validity 10000 -storepass marquee -keypass marquee -dname "CN=Marquee, O=roesler" 2>&1 | Out-Null
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:marquee --key-pass pass:marquee `
  --out "$root\marquee.apk" "$out\marquee-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& "$bt\apksigner.bat" verify "$root\marquee.apk" | Out-Null
$mb = [math]::Round((Get-Item "$root\marquee.apk").Length/1MB,2)
Write-Host "BUILT: $root\marquee.apk ($mb MB)"
