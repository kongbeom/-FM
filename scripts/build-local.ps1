param([string[]]$Tasks = @(':core:test', ':app:assembleDemoDebug', ':app:assemblePleosDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$driveName = 'R:'
$existingMapping = subst.exe | Where-Object { $_.StartsWith($driveName) }
if ($existingMapping -and !$existingMapping.Contains($projectRoot)) { throw 'R: is already in use; choose another free drive in this script.' }
$createdMapping = !$existingMapping
if ($createdMapping) {
    if (Test-Path "$driveName\") { throw 'R: is already in use.' }
    subst.exe $driveName $projectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Could not create temporary ASCII build path.' }
}
$previousJava = $env:JAVA_HOME
$previousGradle = $env:GRADLE_USER_HOME
$previousAndroid = $env:ANDROID_USER_HOME
$localProperties = Join-Path $projectRoot 'local.properties'
$previousProperties = if (Test-Path -LiteralPath $localProperties) { [IO.File]::ReadAllBytes($localProperties) } else { $null }
$locationPushed = $false
try {
    $env:JAVA_HOME = 'R:\.tooling\jdk\jdk-17.0.17+10'
    $env:GRADLE_USER_HOME = 'R:\.tooling\gradle-home-ascii'
    $env:ANDROID_USER_HOME = 'R:\.tooling\android-user'
    Push-Location 'R:\'
    $locationPushed = $true
    [IO.File]::WriteAllText('R:\local.properties', "sdk.dir=R:/.tooling/android-sdk`n", [Text.UTF8Encoding]::new($false))
    & 'R:\.tooling\gradle-8.9\bin\gradle.bat' @Tasks --console=plain --no-daemon
    $buildResult = $LASTEXITCODE
    Pop-Location
    $locationPushed = $false
} finally {
    if ($locationPushed) { Pop-Location }
    if ($null -ne $previousProperties) { [IO.File]::WriteAllBytes($localProperties, $previousProperties) }
    elseif (Test-Path -LiteralPath $localProperties) { Remove-Item -LiteralPath $localProperties }
    $env:JAVA_HOME = $previousJava
    $env:GRADLE_USER_HOME = $previousGradle
    $env:ANDROID_USER_HOME = $previousAndroid
    if ($createdMapping) { subst.exe $driveName /D }
}
exit $buildResult
