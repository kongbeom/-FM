$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\build-local.ps1" -Tasks @(':core:test', ':app:testDemoDebugUnitTest', ':app:connectedDemoDebugAndroidTest')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$PSScriptRoot\build-local.ps1" -Tasks @(':app:connectedPleosDebugAndroidTest', '-Pandroid.testInstrumentationRunnerArguments.class=ai.backfm.NaviAvailabilityTest')
exit $LASTEXITCODE
