# Builds, signs and (optionally) installs Luma without Gradle.
# Settings come from environment variables, or from tools/local.json (git-ignored) with the same names:
#   JAVA_HOME          JDK 17 or newer
#   ANDROID_SDK_ROOT   Android SDK with platforms/android-35 and build-tools/35.0.0
#   LUMA_KEYSTORE      signing keystore (create one with keytool, see README)
#   LUMA_KEYSTORE_PASS keystore password
#   LUMA_DEVICE        optional ADB serial (e.g. 192.168.1.50:5555); when set, the APK is installed and opened
# Run from the repository root: powershell -File tools\build.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$local = Join-Path $PSScriptRoot 'local.json'
$cfg = if (Test-Path $local) { Get-Content $local -Raw | ConvertFrom-Json } else { $null }
function Setting($name, $required = $true) {
    $v = [Environment]::GetEnvironmentVariable($name)
    if (-not $v -and $cfg) { $v = $cfg.$name }
    if (-not $v -and $required) { throw "Set $name (environment variable or tools/local.json)" }
    return $v
}
$jdk = Setting 'JAVA_HOME'; $sdk = Setting 'ANDROID_SDK_ROOT'
$keystore = Setting 'LUMA_KEYSTORE'; $pass = Setting 'LUMA_KEYSTORE_PASS'; $device = Setting 'LUMA_DEVICE' $false
$tools = "$sdk\build-tools\35.0.0"; $jar = "$sdk\platforms\android-35\android.jar"
$out = Join-Path $root 'build'
function Check($step) { if ($LASTEXITCODE -ne 0) { throw "Build step failed: $step" } }

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\classes" | Out-Null
$sources = Get-ChildItem -Recurse -Filter *.java (Join-Path $root 'app\src') | ForEach-Object FullName
& "$jdk\bin\javac.exe" --release 8 -nowarn -encoding UTF-8 -classpath $jar -d "$out\classes" $sources; Check 'javac'
& "$jdk\bin\jar.exe" cf "$out\classes.jar" -C "$out\classes" .; Check 'jar'
& "$tools\d8.bat" --lib $jar --min-api 26 --output $out "$out\classes.jar"; Check 'd8'
& "$tools\aapt.exe" package -f -M "$root\app\AndroidManifest.xml" -S "$root\app\res" -A "$root\app\assets" -I $jar -F "$out\unsigned.apk"; Check 'aapt'
& "$jdk\bin\jar.exe" uf "$out\unsigned.apk" -C $out classes.dex; Check 'jar dex'
& "$tools\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"; Check 'zipalign'
& "$tools\apksigner.bat" sign --ks $keystore --ks-pass "pass:$pass" --out "$out\luma.apk" "$out\aligned.apk"; Check 'apksigner'
Write-Host "Built $out\luma.apk"

if ($device) {
    $adb = "$sdk\platform-tools\adb.exe"
    & $adb -s $device install -r "$out\luma.apk"; Check 'adb install'
    & $adb -s $device shell am start -n com.dhyn.luma/com.dhyn.tvhome.LumaLauncher; Check 'adb start'
}
