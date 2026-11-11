param([string]$Serial = 'emulator-5560')
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$evidencePath = Join-Path $projectPath 'artifacts\acceptance-0.9.0-20261110\process-draft'
New-Item -ItemType Directory -Path $evidencePath -Force | Out-Null
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$avdName = (& $adb -s $Serial emu avd name | Out-String).Trim()
if ($avdName -notmatch '(?m)^MuseumReader_API_[0-9]+(?:-[A-Za-z0-9_-]+)?\r?$') { throw 'Draft process exercise is restricted to a project-owned test AVD' }
$appApk = Join-Path $projectPath 'app\build\outputs\apk\release\app-release.apk'
$testApk = Join-Path $projectPath 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
foreach ($apk in @($appApk, $testApk)) {
    & $adb -s $Serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'Draft exercise APK install failed' }
}
foreach ($phase in @('seed', 'verify')) {
    $raw = & $adb -s $Serial shell am instrument -w -r -e phase $phase com.museum.reader.test/com.museum.reader.acceptance.ProcessDraftRunner 2>&1 | Out-String
    $raw | Set-Content -LiteralPath (Join-Path $evidencePath "$phase.txt") -Encoding utf8
    if ($raw -notmatch 'OK \(1 test\)' -or $raw -match 'FAILURES|INSTRUMENTATION_FAILED|Process crashed') {
        throw "Draft process $phase failed; see evidence log"
    }
    & $adb -s $Serial pull "/sdcard/Android/data/com.museum.reader/files/process-draft-$phase.json" $evidencePath
    if ($LASTEXITCODE -ne 0) { throw 'Unable to retrieve draft exercise result' }
    & $adb -s $Serial shell am force-stop com.museum.reader
    Write-Output "Draft process $phase passed; application force-stopped"
}
$seed = Get-Content (Join-Path $evidencePath 'process-draft-seed.json') -Raw | ConvertFrom-Json
$verified = Get-Content (Join-Path $evidencePath 'process-draft-verify.json') -Raw | ConvertFrom-Json
if ($seed.pid -eq $verified.pid -or $seed.textSha256 -ne $verified.textSha256 -or $seed.titleSha256 -ne $verified.titleSha256) {
    throw 'Draft verification did not prove exact restoration across different application processes'
}
[ordered]@{
    passed=$true;serial=$Serial;timestamp=[DateTimeOffset]::Now.ToString('o')
    seedPid=$seed.pid;verifyPid=$verified.pid;formalUnchanged=$verified.formalUnchanged
    appSha256=(Get-FileHash -LiteralPath $appApk -Algorithm SHA256).Hash
    testSha256=(Get-FileHash -LiteralPath $testApk -Algorithm SHA256).Hash
    sourceCommit=(& git -C $projectPath rev-parse HEAD | Out-String).Trim()
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidencePath 'summary.json') -Encoding utf8
Write-Output 'Exact durable draft restoration verified after actual application force-stop'
