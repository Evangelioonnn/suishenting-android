param(
    [string]$Serial = 'emulator-5560',
    [string]$AppApkPath = '',
    [string[]]$Cases = @('320-100-light','320-150-light','320-200-dark','360-100-dark','360-150-dark','360-200-light','411-100-light','411-150-dark','411-200-light','landscape-320-100-light','landscape-320-150-light','landscape-320-200-dark','landscape-360-100-light','landscape-360-150-dark','landscape-360-200-light','landscape-411-100-dark','landscape-411-150-dark','landscape-411-200-light'),
    [string]$Prefix = 'ui-final'
)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$evidencePath = Join-Path $projectPath 'artifacts\acceptance-0.9.0-20261110'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
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
        }
        if ($AppApkPath) { $argsForTest.AppApkPath = $AppApkPath }
        if (-not $first) { $argsForTest.SkipInstall = $true }
        & (Join-Path $PSScriptRoot 'Run-AndroidTests.ps1') @argsForTest
        $testResult = $LASTEXITCODE
        $destination = Join-Path $evidencePath "ui\$Prefix-$caseName"
        New-Item -ItemType Directory -Path $destination -Force | Out-Null
        & $adb -s $Serial pull /sdcard/Android/data/com.museum.reader/files/ui-evidence $destination
        if ($testResult -ne 0) { throw "UI case failed: $caseName" }
        $first = $false
        Write-Output "UI case completed: $caseName"
    }
} finally {
    & $adb -s $Serial shell wm size reset
    & $adb -s $Serial shell wm density reset
    & $adb -s $Serial shell settings put system font_scale 1.0
    & $adb -s $Serial shell cmd uimode night no
}
