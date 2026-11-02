param([Parameter(Mandatory=$true)][string]$TeamName, [ValidateSet('demo','pleos')][string]$Flavor = 'pleos')
$ErrorActionPreference = 'Stop'
if ($TeamName.IndexOfAny([IO.Path]::GetInvalidFileNameChars()) -ge 0) { throw 'Invalid team name.' }
foreach ($variable in @('BACKFM_KEYSTORE_PATH','BACKFM_STORE_PASSWORD','BACKFM_KEY_ALIAS','BACKFM_KEY_PASSWORD')) {
    if (![Environment]::GetEnvironmentVariable($variable)) { throw "Missing environment variable: $variable" }
}
$root = Split-Path -Parent $PSScriptRoot
$variant = (Get-Culture).TextInfo.ToTitleCase($Flavor)
& "$PSScriptRoot\build-local.ps1" -Tasks @(':core:test', ":app:assemble${variant}Release")
if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
$apk = Join-Path $root "app\build\outputs\apk\$Flavor\release\app-$Flavor-release.apk"
$jdk = Join-Path $root '.tooling\jdk\jdk-17.0.17+10'
$previousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $jdk
    & (Join-Path $root '.tooling\android-sdk\build-tools\34.0.0\apksigner.bat') verify $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
} finally { $env:JAVA_HOME = $previousJava }
$output = Join-Path $root "apk\${TeamName}_BACKFM_release.apk"
Copy-Item -LiteralPath $apk -Destination $output
Write-Output $output
