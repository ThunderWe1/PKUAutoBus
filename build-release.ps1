$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path -LiteralPath $sdkPath)) { throw 'Install Android SDK 36 or set ANDROID_HOME first.' }
if (-not $env:JAVA_HOME) {
    $javaCommand = Get-Command javac -ErrorAction SilentlyContinue
    if ($javaCommand) { $env:JAVA_HOME = Split-Path (Split-Path $javaCommand.Source) }
    else { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
}
if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\keytool.exe'))) { throw 'Set JAVA_HOME to JDK 17 or later.' }
$propertySdk = $sdkPath.Replace('\', '/').Replace(':', '\:')
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'local.properties'), "sdk.dir=$propertySdk`n", [Text.UTF8Encoding]::new($false))

$signingDirectory = Join-Path $env:LOCALAPPDATA 'PKUAutoBus\signing'
$propertiesPath = Join-Path $signingDirectory 'signing.properties'
$keyPath = Join-Path $signingDirectory 'release.jks'
New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
if (-not (Test-Path -LiteralPath $propertiesPath)) {
    if (Test-Path -LiteralPath $keyPath) { throw 'A signing key already exists without its properties file. Restore its credentials; do not replace the key.' }
    $randomBytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($randomBytes) } finally { $random.Dispose() }
    $env:PKUAUTOBUS_STORE_PASS = [Convert]::ToBase64String($randomBytes)
    try {
        & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore $keyPath -storetype PKCS12 -alias pkuautobus -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=PKUAutoBus Community App' -storepass:env PKUAUTOBUS_STORE_PASS -keypass:env PKUAUTOBUS_STORE_PASS -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed.' }
        $escapedKeyPath = $keyPath.Replace('\', '/').Replace(':', '\:')
        $properties = "storeFile=$escapedKeyPath`nstorePassword=$env:PKUAUTOBUS_STORE_PASS`nkeyAlias=pkuautobus`nkeyPassword=$env:PKUAUTOBUS_STORE_PASS`n"
        [IO.File]::WriteAllText($propertiesPath, $properties, [Text.UTF8Encoding]::new($false))
    } finally { Remove-Item Env:\PKUAUTOBUS_STORE_PASS -ErrorAction SilentlyContinue }
}
$env:PKUAUTOBUS_SIGNING_PROPERTIES = $propertiesPath
& .\gradlew.bat testDebugUnitTest lintRelease assembleRelease "-PpkuAutoBusSigningProperties=$propertiesPath" --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build or validation failed.' }

$outputDirectory = Join-Path $PSScriptRoot 'dist'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$metadata = Get-Content -LiteralPath 'app\build\outputs\apk\release\output-metadata.json' -Raw | ConvertFrom-Json
$version = $metadata.elements[0].versionName
$apkPath = Join-Path $outputDirectory "PKUAutoBus-$version.apk"
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk') -Destination $apkPath

Add-Type -AssemblyName System.IO.Compression.FileSystem
$sourcePath = Join-Path $outputDirectory "PKUAutoBus-source-$version.zip"
if (Test-Path -LiteralPath $sourcePath) { Remove-Item -LiteralPath $sourcePath }
$sourceFiles = @(Get-Item -LiteralPath '.gitignore', 'AGENTS.md', 'LICENSE', 'README.md', 'VALIDATION.md', 'THIRD_PARTY_NOTICES.md', 'build-release.ps1', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat', 'app\build.gradle.kts')
$sourceFiles += Get-ChildItem -LiteralPath 'app\src', 'gradle' -Recurse -File
$archive = [IO.Compression.ZipFile]::Open($sourcePath, 'Create')
try {
    foreach ($sourceFile in $sourceFiles) {
        $entry = $sourceFile.FullName.Substring($PSScriptRoot.Length + 1).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $sourceFile.FullName, $entry) | Out-Null
    }
} finally { $archive.Dispose() }
$checksums = @($apkPath, $sourcePath) | ForEach-Object {
    "{0}  {1}" -f (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant(), (Split-Path -Leaf $_)
}
[IO.File]::WriteAllLines((Join-Path $outputDirectory 'SHA256SUMS.txt'), $checksums, [Text.UTF8Encoding]::new($false))
Write-Output "APK: $apkPath"
Write-Output "Source: $sourcePath"
Write-Output "Signing files (keep private and back up): $signingDirectory"
