param([int]$Port = 5560)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $projectRoot 'apk\BACKFM_demo_debug.apk'
$runtimeSettings = Join-Path $projectRoot '.tooling\demo-emulator.json'
if (!(Test-Path -LiteralPath $runtimeSettings)) { throw 'Local emulator setup is missing. Use Android Studio as described in README.md.' }
$settings = Get-Content -LiteralPath $runtimeSettings -Raw | ConvertFrom-Json
$driveName = 'S:'
if (Test-Path "$driveName\") {
    $mappedApk = "$driveName\apk\BACKFM_demo_debug.apk"
    if (!(Test-Path -LiteralPath $mappedApk) -or (Get-FileHash -LiteralPath $mappedApk).Hash -ne (Get-FileHash -LiteralPath $apk).Hash) {
        throw 'S: is in use by another folder. Close that mapping before using this local launcher.'
    }
} else {
    subst.exe $driveName $projectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the local Android tool path.' }
}
$sdk = "$driveName\.tooling\android-sdk"
$adb = "$sdk\platform-tools\adb.exe"
if (!(Test-Path -LiteralPath $adb)) { throw 'Local Android SDK is missing. See README.md.' }
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_USER_HOME = "$driveName\.tooling\android-user"
$env:ANDROID_AVD_HOME = $settings.avdHome
$serial = "emulator-$Port"
& $adb start-server
$connected = (& $adb devices) -match "^$serial\s"
if (!$connected) {
    $config = Join-Path $settings.avdHome "$($settings.avdName).avd\config.ini"
    $configText = [IO.File]::ReadAllText($config)
    $configText = [regex]::Replace($configText, '(?m)^hw\.audioOutput\s*=.*$', 'hw.audioOutput = yes')
    [IO.File]::WriteAllText($config, $configText, [Text.UTF8Encoding]::new($false))
    Write-Host 'Opening the Android emulator window...'
    Start-Process -FilePath "$sdk\emulator\emulator.exe" -ArgumentList '-avd',$settings.avdName,'-port',"$Port",'-no-snapshot','-no-boot-anim','-gpu','swiftshader','-memory','2048' -WindowStyle Normal -RedirectStandardOutput "$driveName\.tooling\demo-emulator.log" -RedirectStandardError "$driveName\.tooling\demo-emulator-error.log"
}
Write-Host 'Waiting for Android to start...'
$booted = $false
for ($attempt = 0; $attempt -lt 120; $attempt++) {
    if ((& $adb devices) -match "^$serial\s+device") {
        $booted = ((& $adb -s $serial shell getprop sys.boot_completed) -join '').Trim() -eq '1'
        if ($booted) { break }
    }
    Start-Sleep -Seconds 1
}
if (!$booted) { throw 'Android did not finish starting. Check .tooling/demo-emulator-error.log.' }
$name = & $adb -s $serial emu avd name
if ($name -notcontains $settings.avdName) { throw 'The selected emulator belongs to another AVD; nothing was installed.' }
& $adb -s $serial install -r "$driveName\apk\BACKFM_demo_debug.apk"
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
& $adb -s $serial shell am start -n ai.backfm.demo/ai.backfm.ui.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'Could not open BACKFM.' }
Write-Host 'BACKFM is open in the emulator. Keep the S: mapping while the emulator is running.'
