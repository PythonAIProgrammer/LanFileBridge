$ErrorActionPreference = "Stop"

$ProjectRoot = $PSScriptRoot
$WorkspaceRoot = Resolve-Path (Join-Path $ProjectRoot "..\..\..")
$SdkRoot = Join-Path $WorkspaceRoot "work\android-sdk"
$BuildTools = Join-Path $SdkRoot "build-tools\35.0.0"
$AndroidJar = Join-Path $SdkRoot "platforms\android-35\android.jar"
$Jdk = "C:\Program Files\JetBrains\PyCharm 2025.1.3\jbr"
$env:JAVA_HOME = $Jdk
$env:Path = "$Jdk\bin;$env:Path"
$AppRoot = Join-Path $ProjectRoot "app\src\main"
$Out = Join-Path $ProjectRoot "manual-build"
$Package = "com.lanbridge.app"
$Unsigned = Join-Path $Out "LanFileBridge-unsigned.apk"
$Aligned = Join-Path $Out "LanFileBridge-aligned.apk"
$Signed = Join-Path $WorkspaceRoot "outputs\LanFileBridge-android-debug.apk"
$Keystore = Join-Path $Out "debug.keystore"
$LocalAndroidJar = Join-Path $Out "android.jar"
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Invoke-Checked {
  param(
    [Parameter(Mandatory = $true)][string]$FilePath,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments
  )
  & $FilePath @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "Command failed with exit code ${LASTEXITCODE}: $FilePath $($Arguments -join ' ')"
  }
}

New-Item -ItemType Directory -Force -Path $Out | Out-Null
$CompiledResDir = Join-Path $Out "compiled-res"
$ClassesDir = Join-Path $Out "classes"
$DexDir = Join-Path $Out "dex"
$ClassesJar = Join-Path $Out "classes.jar"
Remove-Item -Recurse -Force $CompiledResDir, $ClassesDir, $DexDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $CompiledResDir | Out-Null
New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null
New-Item -ItemType Directory -Force -Path $DexDir | Out-Null
Copy-Item -Force $AndroidJar $LocalAndroidJar

$Args = @("compile", "--dir", (Join-Path $AppRoot "res"), "-o", $CompiledResDir)
Invoke-Checked (Join-Path $BuildTools "aapt2.exe") @Args

$CompiledResources = Get-ChildItem -Path $CompiledResDir -Filter "*.flat" | ForEach-Object { $_.FullName }
$Args = @(
  "link",
  "-o", $Unsigned,
  "-I", $LocalAndroidJar,
  "--manifest", (Join-Path $AppRoot "AndroidManifest.xml"),
  "--java", (Join-Path $Out "generated"),
  "--min-sdk-version", "26",
  "--target-sdk-version", "35",
  "--version-code", "1",
  "--version-name", "1.0",
  "--rename-manifest-package", $Package
) + $CompiledResources
Invoke-Checked (Join-Path $BuildTools "aapt2.exe") @Args

$Sources = @()
$Sources += Get-ChildItem -Path (Join-Path $AppRoot "java") -Filter "*.java" -Recurse | ForEach-Object { $_.FullName }
$Sources += Get-ChildItem -Path (Join-Path $Out "generated") -Filter "*.java" -Recurse | ForEach-Object { $_.FullName }
$Args = @(
  "-encoding", "UTF-8",
  "-source", "8",
  "-target", "8",
  "-classpath", $LocalAndroidJar,
  "-d", $ClassesDir
) + $Sources
Invoke-Checked (Join-Path $Jdk "bin\javac.exe") @Args

Remove-Item -Force $ClassesJar -ErrorAction SilentlyContinue
[System.IO.Compression.ZipFile]::CreateFromDirectory($ClassesDir, $ClassesJar)

$Args = @(
  "--lib", $LocalAndroidJar,
  "--min-api", "26",
  "--output", $DexDir,
  $ClassesJar
)
Invoke-Checked (Join-Path $BuildTools "d8.bat") @Args

Copy-Item $Unsigned (Join-Path $Out "with-dex.zip") -Force
$ApkZip = Join-Path $Out "with-dex.zip"
$Zip = [System.IO.Compression.ZipFile]::Open($ApkZip, [System.IO.Compression.ZipArchiveMode]::Update)
try {
  $Existing = $Zip.GetEntry("classes.dex")
  if ($Existing -ne $null) {
    $Existing.Delete()
  }
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Zip, (Join-Path $DexDir "classes.dex"), "classes.dex") | Out-Null
} finally {
  $Zip.Dispose()
}
Move-Item -Force (Join-Path $Out "with-dex.zip") $Unsigned

$Args = @("-f", "-p", "4", $Unsigned, $Aligned)
Invoke-Checked (Join-Path $BuildTools "zipalign.exe") @Args

if (-not (Test-Path $Keystore)) {
  $Args = @(
    "-genkeypair",
    "-keystore", $Keystore,
    "-storepass", "android",
    "-keypass", "android",
    "-alias", "androiddebugkey",
    "-keyalg", "RSA",
    "-keysize", "2048",
    "-validity", "10000",
    "-dname", "CN=Android Debug,O=Android,C=US"
  )
  Invoke-Checked (Join-Path $Jdk "bin\keytool.exe") @Args
}

$Args = @(
  "sign",
  "--ks", $Keystore,
  "--ks-pass", "pass:android",
  "--key-pass", "pass:android",
  "--out", $Signed,
  $Aligned
)
Invoke-Checked (Join-Path $BuildTools "apksigner.bat") @Args

$Args = @("verify", "--verbose", $Signed)
Invoke-Checked (Join-Path $BuildTools "apksigner.bat") @Args
Write-Host "APK: $Signed"
