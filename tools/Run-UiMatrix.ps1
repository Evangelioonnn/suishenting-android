param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$AppApkPath,
    [Parameter(Mandatory=$true)][string]$TestApkPath,
    [Parameter(Mandatory=$true)][string]$SourceManifest,
    [string]$EvidenceDirectory = '',
    [switch]$ValidateOnly,
    [string[]]$Cases = @('320-100-light','320-150-light','320-200-dark','360-100-dark','360-150-dark','360-200-light','411-100-light','411-150-dark','411-200-light','landscape-320-100-light','landscape-320-150-light','landscape-320-200-dark','landscape-360-100-light','landscape-360-150-dark','landscape-360-200-light','landscape-411-100-dark','landscape-411-150-dark','landscape-411-200-light'),
    [string]$Prefix = 'ui-final'
)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'AcceptanceInputs.ps1')
$inputs = Get-AcceptanceInputs $AppApkPath $TestApkPath $SourceManifest
if ($Prefix -notmatch '^[A-Za-z0-9_-]+$') { throw 'Use a simple evidence prefix' }
if (-not $EvidenceDirectory) { $EvidenceDirectory = Join-Path $projectPath ('artifacts\ui-matrix-' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $EvidenceDirectory) { throw 'Preserve existing evidence; choose a new directory' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
Assert-AcceptanceDevice $adb $Serial
$presets = @{
    '360-100-dark' = @(1080,2160,480,'1.0','yes')
    '360-200-light' = @(1080,2160,480,'2.0','no')
    '411-100-light' = @(1080,1920,420,'1.0','no')
    '411-150-dark' = @(1080,1920,420,'1.5','yes')
    'landscape-320-100-light' = @(1800,960,480,'1.0','no')
    'landscape-320-150-light' = @(1800,960,480,'1.5','no')
    'landscape-360-150-dark' = @(2160,1080,480,'1.5','yes')
    'landscape-360-200-light' = @(2160,1080,480,'2.0','no')
    'landscape-411-100-dark' = @(1920,1080,420,'1.0','yes')
    'landscape-411-200-light' = @(1920,1080,420,'2.0','no')
    '320-100-light' = @(960,1800,480,'1.0','no')
    '320-150-light' = @(960,1800,480,'1.5','no')
    '360-150-dark' = @(1080,2160,480,'1.5','yes')
    '411-200-light' = @(1080,1920,420,'2.0','no')
    '320-200-dark' = @(960,1800,480,'2.0','yes')
    'landscape-320-200-dark' = @(1800,960,480,'2.0','yes')
    'landscape-360-100-light' = @(2160,1080,480,'1.0','no')
    'landscape-411-150-dark' = @(1920,1080,420,'1.5','yes')
}
$first = $true
if ($Cases.Count -eq 0 -or @($Cases | Select-Object -Unique).Count -ne $Cases.Count) { throw 'UI cases must be nonempty and unique' }
foreach ($caseName in $Cases) { if (-not $presets.ContainsKey($caseName)) { throw "Unknown UI case: $caseName" } }
if ($ValidateOnly) { Write-Output 'Frozen inputs, unique cases and destination, and test AVD verified; no display or files changed'; return }
$evidencePath = New-AcceptanceEvidence $EvidenceDirectory
Copy-Item -LiteralPath $inputs.ManifestPath -Destination (Join-Path $evidencePath 'source-manifest.json')
$originalSize = (& $adb -s $Serial shell wm size | Out-String)
$originalDensity = (& $adb -s $Serial shell wm density | Out-String)
$originalFont = (& $adb -s $Serial shell settings get system font_scale | Out-String).Trim()
$originalNight = (& $adb -s $Serial shell cmd uimode night | Out-String).Trim()
[ordered]@{size=$originalSize;density=$originalDensity;font=$originalFont;night=$originalNight} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidencePath 'display-before.json') -Encoding utf8
try {
    foreach ($caseName in $Cases) {
        if (-not $presets.ContainsKey($caseName)) { throw "Unknown UI case: $caseName" }
        $preset = $presets[$caseName]
        & $adb -s $Serial shell wm size "$($preset[0])x$($preset[1])"
        & $adb -s $Serial shell wm density $preset[2]
        & $adb -s $Serial shell settings put system font_scale $preset[3]
        & $adb -s $Serial shell cmd uimode night $preset[4]
        Start-Sleep -Milliseconds 500
        $argsForTest = @{
            Serial=$Serial; TestClass='com.museum.reader.ui.UiMatrixTest'
            EvidenceName="$Prefix-$caseName"
            DeviceEvidenceName="$Prefix-$caseName"
            EvidenceDirectory=$evidencePath;SourceManifest=$SourceManifest
            AppApkPath=$inputs.App;TestApkPath=$inputs.Test;ExpectedTestCount=1
        }
        if (-not $first) { $argsForTest.SkipInstall = $true }
        & (Join-Path $PSScriptRoot 'Run-AndroidTests.ps1') @argsForTest
        $testResult = $LASTEXITCODE
        $destination = Join-Path $evidencePath "ui\$Prefix-$caseName"
        New-Item -ItemType Directory -Path $destination | Out-Null
        & $adb -s $Serial pull "/sdcard/Android/data/com.museum.reader/files/supplemental-evidence-$Prefix-$caseName" $destination
        if ($LASTEXITCODE -ne 0) { throw 'Unable to retrieve unique UI evidence' }
        if ($testResult -ne 0) { throw "UI case failed: $caseName" }
        $first = $false
        Write-Output "UI case completed: $caseName"
    }
} finally {
    if ($originalSize -match 'Override size: ([0-9]+x[0-9]+)') { & $adb -s $Serial shell wm size $Matches[1] }
    else { & $adb -s $Serial shell wm size reset }
    if ($originalDensity -match 'Override density: ([0-9]+)') { & $adb -s $Serial shell wm density $Matches[1] }
    else { & $adb -s $Serial shell wm density reset }
    if ($originalFont -eq 'null' -or -not $originalFont) { & $adb -s $Serial shell settings delete system font_scale }
    else { & $adb -s $Serial shell settings put system font_scale $originalFont }
    if ($originalNight -match '(?:Night mode:|night)\s*(auto|yes|no|custom)') { & $adb -s $Serial shell cmd uimode night $Matches[1] }
}
