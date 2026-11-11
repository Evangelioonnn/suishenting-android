param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$OriginalApkPath = '',
    [string]$CandidateApkPath = ''
)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$evidencePath = Join-Path $projectPath 'artifacts\acceptance-0.9.0-20261110\upgrade'
New-Item -ItemType Directory -Path $evidencePath -Force | Out-Null
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$apksigner = Join-Path $env:ANDROID_HOME 'build-tools\36.1.0\apksigner.bat'
if (-not $OriginalApkPath) { $OriginalApkPath = Join-Path $projectPath 'releases\MuseumReader-v0.8.0-debug.apk' }
if (-not $CandidateApkPath) { $CandidateApkPath = Join-Path $projectPath 'app\build\outputs\apk\release\app-release.apk' }
$testApk = Join-Path $projectPath 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
$existing = & $adb -s $Serial shell pm path com.museum.reader
if ($existing) { throw 'Upgrade exercise requires a fresh project-owned test AVD; no existing application data will be overwritten' }
$certificate = '2b5e9492af068d504ba3fccc866cb41781d2a4e2c33e6dd56abae087cc46c0d9'
foreach ($apk in @($OriginalApkPath, $CandidateApkPath, $testApk)) {
    $verification = & $apksigner verify --print-certs $apk 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $verification -notmatch $certificate) { throw 'APK signature does not match the preserved baseline certificate' }
}
function Invoke-UpgradeRunner([string]$Runner, [string]$Name) {
    $raw = & $adb -s $Serial shell am instrument -w -r "com.museum.reader.test/$Runner" 2>&1 | Out-String
    $raw | Set-Content -LiteralPath (Join-Path $evidencePath "$Name.txt") -Encoding utf8
    if ($raw -notmatch 'OK \(1 test\)' -or $raw -match 'FAILURES|INSTRUMENTATION_FAILED|Process crashed') {
        throw "Upgrade runner failed; see $Name.txt"
    }
    Write-Output "$Name passed"
}
& $adb -s $Serial install -r $OriginalApkPath
if ($LASTEXITCODE -ne 0) { throw 'Original APK installation failed' }
& $adb -s $Serial install -r $testApk
if ($LASTEXITCODE -ne 0) { throw 'Acceptance instrumentation installation failed' }
Invoke-UpgradeRunner 'com.museum.reader.acceptance.UpgradeSeedRunner' 'seed-v080'
& $adb -s $Serial shell am start -W -n com.museum.reader/.MainActivity | Out-File (Join-Path $evidencePath 'launch-v080.txt') -Encoding utf8
Start-Sleep -Milliseconds 1000
& $adb -s $Serial shell screencap -p /sdcard/upgrade-v080.png
& $adb -s $Serial pull /sdcard/upgrade-v080.png (Join-Path $evidencePath 'before.png')
& $adb -s $Serial pull /sdcard/Android/data/com.museum.reader/files/upgrade-seed.json $evidencePath
& $adb -s $Serial shell am force-stop com.museum.reader
& $adb -s $Serial install -r $CandidateApkPath
if ($LASTEXITCODE -ne 0) { throw 'Candidate cover-upgrade failed' }
Invoke-UpgradeRunner 'com.museum.reader.acceptance.UpgradeVerifyRunner' 'verify-v090'
& $adb -s $Serial shell am start -W -n com.museum.reader/.MainActivity | Out-File (Join-Path $evidencePath 'launch-v090.txt') -Encoding utf8
Start-Sleep -Milliseconds 1000
& $adb -s $Serial shell screencap -p /sdcard/upgrade-v090.png
& $adb -s $Serial pull /sdcard/upgrade-v090.png (Join-Path $evidencePath 'after.png')
& $adb -s $Serial pull /sdcard/Android/data/com.museum.reader/files/upgrade-verified.json $evidencePath
[ordered]@{
    passed=$true;serial=$Serial;timestamp=[DateTimeOffset]::Now.ToString('o')
    originalApkSha256=(Get-FileHash -LiteralPath $OriginalApkPath -Algorithm SHA256).Hash
    candidateApkSha256=(Get-FileHash -LiteralPath $CandidateApkPath -Algorithm SHA256).Hash
    testApkSha256=(Get-FileHash -LiteralPath $testApk -Algorithm SHA256).Hash
    certificateSha256=$certificate;sourceCommit=(& git -C $projectPath rev-parse HEAD | Out-String).Trim()
    scope='Actual v0.8.0 to internal Release v0.9.0 upgrade on a fresh project-owned emulator using synthetic v1 data'
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidencePath 'summary.json') -Encoding utf8
Write-Output 'Actual APK cover-upgrade verified; all synthetic source data retained'
