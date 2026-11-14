param(
    [string]$Serial = 'emulator-5556',
    [string]$TestClass = '',
    [string]$EvidenceName = 'instrumentation',
    [string]$AppApkPath = '',
    [string]$TestApkPath = '',
    [string]$EvidenceDirectory = '',
    [string]$SourceManifest = '',
    [int]$ExpectedTestCount = 0,
    [string]$DeviceEvidenceName = '',
    [switch]$SkipInstall,
    [string]$AdbPath = "$env:ANDROID_HOME\platform-tools\adb.exe"
)

$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$evidencePath = Join-Path $projectPath 'artifacts\acceptance-0.9.0-20261110'
if ($EvidenceDirectory) { $evidencePath = [IO.Path]::GetFullPath($EvidenceDirectory) }
if ($EvidenceName -notmatch '^[A-Za-z0-9_-]+$') { throw 'Use a simple unique evidence name' }
foreach ($extension in @('txt', 'json')) {
    if (Test-Path -LiteralPath (Join-Path $evidencePath "$EvidenceName.$extension")) { throw 'Preserve existing evidence; choose a new name' }
}
New-Item -ItemType Directory -Path $evidencePath -Force | Out-Null
$appApk = Join-Path $projectPath 'app\build\outputs\apk\debug\app-debug.apk'
if ($AppApkPath) { $appApk = (Resolve-Path -LiteralPath $AppApkPath).Path }
$testApk = Join-Path $projectPath 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
if ($TestApkPath) { $testApk = (Resolve-Path -LiteralPath $TestApkPath).Path }
$appHash = (Get-FileHash -LiteralPath $appApk -Algorithm SHA256).Hash
$testHash = (Get-FileHash -LiteralPath $testApk -Algorithm SHA256).Hash
if ($SourceManifest) {
    $frozen = Get-Content -LiteralPath $SourceManifest -Raw | ConvertFrom-Json
    if ($frozen.appSha256 -ne $appHash -or $frozen.testSha256 -ne $testHash) { throw 'Input APKs do not match the frozen source manifest' }
    $sourceCommit = $frozen.sourceCommit
    $sourceFiles = $frozen.sourceFiles
    $workingChanges = @()
    $sourceMode = 'frozen-source-manifest'
} else {
    $sourceCommit = (& git -C $projectPath rev-parse HEAD | Out-String).Trim()
    $workingChanges = @(& git -C $projectPath -c core.quotepath=false status --short)
    $sourceFiles = @(& rg --files app/src app/schemas app/build.gradle.kts build.gradle.kts settings.gradle.kts gradle.properties gradle |
        ForEach-Object { [ordered]@{ path=$_;sha256=(Get-FileHash -LiteralPath (Join-Path $projectPath $_) -Algorithm SHA256).Hash } })
    $sourceMode = 'current-unfrozen'
}
if (-not $SkipInstall) {
    foreach ($apk in @($appApk, $testApk)) {
        & $AdbPath -s $Serial install -r $apk
        if ($LASTEXITCODE -ne 0) { throw "Unable to install test APK: $apk" }
    }
} else {
    foreach ($pair in @(@('com.museum.reader', $appApk), @('com.museum.reader.test', $testApk))) {
        $packagePath = (& $AdbPath -s $Serial shell pm path $pair[0] | Select-Object -First 1) -replace '^package:', ''
        if ($packagePath -notmatch '^/data/app/[^\s]+/base\.apk$') { throw 'Installed APK path could not be verified' }
        $hashLine = & $AdbPath -s $Serial shell sha256sum $packagePath.Trim()
        $actualHash = ("$hashLine" -split '\s+')[0]
        if ($actualHash -ine (Get-FileHash -LiteralPath $pair[1] -Algorithm SHA256).Hash) { throw 'Installed APK does not match test evidence input' }
    }
}
$runnerArgs = @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'evidenceName', $EvidenceName)
if ($DeviceEvidenceName) {
    if ($DeviceEvidenceName -notmatch '^[A-Za-z0-9_-]+$') { throw 'Use a simple unique device evidence name' }
    $runnerArgs += @('-e', 'evidenceDir', $DeviceEvidenceName)
}
if ($TestClass) { $runnerArgs += @('-e', 'class', $TestClass) }
else {
    # Dedicated scenarios require actual TalkBack, a long measurement run, or an empty synthetic app.
    $excluded = 'com.museum.reader.ui.ActualTalkBackFlowTest,com.museum.reader.ui.LocalPerformanceTest,com.museum.reader.ui.BackupExportIntegrityUiTest'
    $runnerArgs += @('-e', 'notClass', $excluded)
}
$runnerArgs += 'com.museum.reader.test/androidx.test.runner.AndroidJUnitRunner'
$raw = & $AdbPath @runnerArgs 2>&1 | Out-String
$raw | Set-Content -LiteralPath (Join-Path $evidencePath "$EvidenceName.txt") -Encoding utf8
$passed = $raw -match 'OK \((\d+) tests?\)' -and $raw -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed'
$testCount = if ($passed) { [int][regex]::Match($raw, 'OK \((\d+) tests?\)').Groups[1].Value } else { 0 }
$resultCodes = @([regex]::Matches($raw, '(?m)^INSTRUMENTATION_STATUS_CODE: (-?[0-9]+)\s*$') | ForEach-Object { [int]$_.Groups[1].Value } | Where-Object { $_ -ne 1 })
$skipped = @($resultCodes | Where-Object { $_ -ne 0 -and $_ -ne -1 -and $_ -ne -2 }).Count
if ($skipped -gt 0 -or $resultCodes.Count -ne $testCount -or ($ExpectedTestCount -gt 0 -and $testCount -ne $ExpectedTestCount)) { $passed = $false }
$deviceInfo = & $AdbPath -s $Serial shell getprop ro.build.version.sdk
[ordered]@{
    serial = $Serial
    api = "$deviceInfo".Trim()
    timestamp = [DateTimeOffset]::Now.ToString('o')
    testClass = $TestClass
    passed = $passed
    passedTests = $testCount
    expectedTests = $ExpectedTestCount
    skippedTests = $skipped
    completedStatusCount = $resultCodes.Count
    appSha256 = $appHash
    testSha256 = $testHash
    sourceCommit = $sourceCommit
    workingChanges = $workingChanges
    sourceFiles = $sourceFiles
    sourceMode = $sourceMode
    sourceManifest = $SourceManifest
    excludedDedicatedScenarios = if ($TestClass) { '' } else { $excluded }
    rawEvidence = "$EvidenceName.txt"
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidencePath "$EvidenceName.json") -Encoding utf8
if ($passed) {
    Write-Output ("PASS: " + $testCount + " instrumentation tests on " + $Serial + ". Evidence: " + $EvidenceName + ".txt")
} else {
    $raw -split "\r?\n" | Select-String -Pattern '^\d+\)|^Tests run:|^There (was|were)|^Time:|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS: stack=' -Context 0,2 |
        ForEach-Object { $_.ToString() } | Write-Output
    Write-Output "Full diagnostic saved to $EvidenceName.txt"
    exit 1
}
