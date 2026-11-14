param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$OriginalApkPath,
    [Parameter(Mandatory=$true)][string]$OriginalApkSha256,
    [Parameter(Mandatory=$true)][string]$CandidateApkPath,
    [Parameter(Mandatory=$true)][string]$TestApkPath,
    [Parameter(Mandatory=$true)][string]$SourceManifest,
    [string]$EvidenceDirectory = '',
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'AcceptanceInputs.ps1')
$inputs = Get-AcceptanceInputs $CandidateApkPath $TestApkPath $SourceManifest
$OriginalApkPath = (Resolve-Path -LiteralPath $OriginalApkPath).Path
if ($OriginalApkSha256 -notmatch '^[A-Fa-f0-9]{64}$' -or (Get-FileHash -LiteralPath $OriginalApkPath -Algorithm SHA256).Hash -ine $OriginalApkSha256) { throw 'Original APK hash differs from frozen baseline' }
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $projectPath ('artifacts\upgrade-' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Preserve existing evidence; choose a new directory' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$apksigner = Join-Path $env:ANDROID_HOME 'build-tools\36.1.0\apksigner.bat'
$aapt = Join-Path $env:ANDROID_HOME 'build-tools\36.1.0\aapt2.exe'
$baselineIdentity = & $aapt dump badging $OriginalApkPath 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or $baselineIdentity -notmatch "versionCode='8'") { throw 'This fixture requires the original v0.8.0 APK' }
Assert-AcceptanceDevice $adb $Serial
$CandidateApkPath = $inputs.App; $testApk = $inputs.Test
$existing = & $adb -s $Serial shell pm path com.museum.reader
if ($existing) { throw 'Upgrade exercise requires a fresh project-owned test AVD; no existing application data will be overwritten' }
$certificate = '2b5e9492af068d504ba3fccc866cb41781d2a4e2c33e6dd56abae087cc46c0d9'
foreach ($apk in @($OriginalApkPath, $CandidateApkPath, $testApk)) {
    $verification = & $apksigner verify --print-certs $apk 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $verification -notmatch $certificate) { throw 'APK signature does not match the preserved baseline certificate' }
}
if ($ValidateOnly) { Write-Output 'Frozen inputs, certificate, unique destination and fresh test AVD verified; no files or packages changed'; return }
$evidencePath = New-AcceptanceEvidence $EvidenceDirectory
Copy-Item -LiteralPath $inputs.ManifestPath -Destination (Join-Path $evidencePath 'source-manifest.json')
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
Assert-InstalledAcceptanceApk $adb $Serial 'com.museum.reader' $OriginalApkSha256
& $adb -s $Serial install -r $testApk
if ($LASTEXITCODE -ne 0) { throw 'Acceptance instrumentation installation failed' }
Assert-InstalledAcceptanceApk $adb $Serial 'com.museum.reader.test' $inputs.TestHash
Invoke-UpgradeRunner 'com.museum.reader.acceptance.UpgradeSeedRunner' 'seed-v080'
& $adb -s $Serial shell am start -W -n com.museum.reader/.MainActivity | Out-File (Join-Path $evidencePath 'launch-v080.txt') -Encoding utf8
Start-Sleep -Milliseconds 1000
& $adb -s $Serial shell screencap -p /sdcard/upgrade-v080.png
& $adb -s $Serial pull /sdcard/upgrade-v080.png (Join-Path $evidencePath 'before.png')
& $adb -s $Serial pull /sdcard/Android/data/com.museum.reader/files/upgrade-seed.json $evidencePath
& $adb -s $Serial shell am force-stop com.museum.reader
& $adb -s $Serial install -r $CandidateApkPath
if ($LASTEXITCODE -ne 0) { throw 'Candidate cover-upgrade failed' }
Assert-InstalledAcceptanceApk $adb $Serial 'com.museum.reader' $inputs.AppHash
Invoke-UpgradeRunner 'com.museum.reader.acceptance.UpgradeVerifyRunner' 'verify-candidate'
& $adb -s $Serial shell am start -W -n com.museum.reader/.MainActivity | Out-File (Join-Path $evidencePath 'launch-candidate.txt') -Encoding utf8
Start-Sleep -Milliseconds 1000
& $adb -s $Serial shell screencap -p /sdcard/upgrade-candidate.png
& $adb -s $Serial pull /sdcard/upgrade-candidate.png (Join-Path $evidencePath 'after.png')
& $adb -s $Serial pull /sdcard/Android/data/com.museum.reader/files/upgrade-verified.json $evidencePath
if ($LASTEXITCODE -ne 0) { throw 'Unable to retrieve upgrade result' }
$verified = Get-Content -LiteralPath (Join-Path $evidencePath 'upgrade-verified.json') -Raw | ConvertFrom-Json
if (-not $verified.passed -or -not $verified.audioHashesPreserved -or -not $verified.settingsPreserved) { throw 'Upgrade result did not confirm data preservation' }
foreach ($required in @('before.png','after.png','upgrade-seed.json')) {
    if (-not (Test-Path -LiteralPath (Join-Path $evidencePath $required))) { throw 'Upgrade evidence is incomplete' }
}
[ordered]@{
    passed=$true;serial=$Serial;timestamp=[DateTimeOffset]::Now.ToString('o')
    originalApkSha256=(Get-FileHash -LiteralPath $OriginalApkPath -Algorithm SHA256).Hash
    candidateApkSha256=(Get-FileHash -LiteralPath $CandidateApkPath -Algorithm SHA256).Hash
    testApkSha256=(Get-FileHash -LiteralPath $testApk -Algorithm SHA256).Hash
    certificateSha256=$certificate;sourceCommit=$inputs.Manifest.sourceCommit;sourceManifestSha256=(Get-FileHash -LiteralPath $inputs.ManifestPath -Algorithm SHA256).Hash
    scope='Actual v0.8.0 to frozen candidate upgrade on a fresh project-owned emulator using synthetic v1 data'
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidencePath 'summary.json') -Encoding utf8
Write-Output 'Actual APK cover-upgrade verified; all synthetic source data retained'
