param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$AppApkPath,
    [Parameter(Mandatory=$true)][string]$TestApkPath,
    [Parameter(Mandatory=$true)][string]$SourceManifest,
    [string]$EvidenceDirectory = '',
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'AcceptanceInputs.ps1')
$inputs = Get-AcceptanceInputs $AppApkPath $TestApkPath $SourceManifest
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $projectPath ('artifacts\process-draft-' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Preserve existing evidence; choose a new directory' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
Assert-AcceptanceDevice $adb $Serial
if ($ValidateOnly) { Write-Output 'Frozen inputs, unique destination and test AVD verified; no files or packages changed'; return }
$evidencePath = New-AcceptanceEvidence $EvidenceDirectory
$appApk = $inputs.App; $testApk = $inputs.Test
Copy-Item -LiteralPath $inputs.ManifestPath -Destination (Join-Path $evidencePath 'source-manifest.json')
foreach ($apk in @($appApk, $testApk)) {
    & $adb -s $Serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'Draft exercise APK install failed' }
}
Assert-InstalledAcceptanceApk $adb $Serial 'com.museum.reader' $inputs.AppHash
Assert-InstalledAcceptanceApk $adb $Serial 'com.museum.reader.test' $inputs.TestHash
foreach ($phase in @('seed', 'verify')) {
    $raw = & $adb -s $Serial shell am instrument -w -r -e phase $phase com.museum.reader.test/com.museum.reader.acceptance.ProcessDraftRunner 2>&1 | Out-String
    $raw | Set-Content -LiteralPath (Join-Path $evidencePath "$phase.txt") -Encoding utf8
    if ($raw -notmatch 'OK \(1 test\)' -or $raw -match 'FAILURES|INSTRUMENTATION_FAILED|Process crashed') {
        throw "Draft process $phase failed; see evidence log"
    }
    & $adb -s $Serial pull "/sdcard/Android/data/com.museum.reader/files/process-draft-$phase.json" $evidencePath
    if ($LASTEXITCODE -ne 0) { throw 'Unable to retrieve draft exercise result' }
    & $adb -s $Serial shell am force-stop com.museum.reader
    if ($LASTEXITCODE -ne 0) { throw 'Application force-stop failed' }
    Write-Output "Draft process $phase passed; application force-stopped"
}
$seed = Get-Content (Join-Path $evidencePath 'process-draft-seed.json') -Raw | ConvertFrom-Json
$verified = Get-Content (Join-Path $evidencePath 'process-draft-verify.json') -Raw | ConvertFrom-Json
if (-not $seed.passed -or -not $verified.passed -or -not $verified.formalUnchanged -or $seed.pid -eq $verified.pid -or $seed.textSha256 -ne $verified.textSha256 -or $seed.titleSha256 -ne $verified.titleSha256) {
    throw 'Draft verification did not prove exact restoration across different application processes'
}
[ordered]@{
    passed=$true;serial=$Serial;timestamp=[DateTimeOffset]::Now.ToString('o')
    seedPid=$seed.pid;verifyPid=$verified.pid;formalUnchanged=$verified.formalUnchanged
    appSha256=(Get-FileHash -LiteralPath $appApk -Algorithm SHA256).Hash
    testSha256=(Get-FileHash -LiteralPath $testApk -Algorithm SHA256).Hash
    sourceCommit=$inputs.Manifest.sourceCommit;sourceManifestSha256=(Get-FileHash -LiteralPath $inputs.ManifestPath -Algorithm SHA256).Hash
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidencePath 'summary.json') -Encoding utf8
Write-Output 'Exact durable draft restoration verified after actual application force-stop'
