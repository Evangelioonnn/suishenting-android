# Shared preflight for evidence-producing tools. No APK or device data is mutated here.
function Get-AcceptanceInputs {
    param([string]$AppApkPath, [string]$TestApkPath, [string]$SourceManifest)
    $manifestPath = (Resolve-Path -LiteralPath $SourceManifest).Path
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $app = (Resolve-Path -LiteralPath $AppApkPath).Path
    $test = (Resolve-Path -LiteralPath $TestApkPath).Path
    $appHash = (Get-FileHash -LiteralPath $app -Algorithm SHA256).Hash
    $testHash = (Get-FileHash -LiteralPath $test -Algorithm SHA256).Hash
    if ($manifest.appSha256 -ine $appHash -or $manifest.testSha256 -ine $testHash) {
        throw 'Input APKs do not match the frozen source manifest'
    }
    [pscustomobject]@{ App=$app; Test=$test; AppHash=$appHash; TestHash=$testHash; Manifest=$manifest; ManifestPath=$manifestPath }
}

function Assert-AcceptanceDevice {
    param([string]$Adb, [string]$Serial)
    if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Use a project-owned test emulator' }
    $avd = & $Adb -s $Serial emu avd name 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $avd -notmatch '(?m)^MuseumReader_API_[0-9]+(?:-[A-Za-z0-9_-]+)?\r?$') {
        throw 'Acceptance exercise is restricted to a project-owned test AVD'
    }
    $boot = & $Adb -s $Serial shell getprop sys.boot_completed
    if ($LASTEXITCODE -ne 0 -or "$boot".Trim() -ne '1') { throw 'Test emulator is not ready' }
}

function New-AcceptanceEvidence {
    param([string]$Path)
    $absolute = [IO.Path]::GetFullPath($Path)
    if (Test-Path -LiteralPath $absolute) { throw 'Preserve existing evidence; choose a new directory' }
    New-Item -ItemType Directory -Path $absolute | Out-Null
    $absolute
}

function Assert-InstalledAcceptanceApk {
    param([string]$Adb, [string]$Serial, [string]$Package, [string]$ExpectedHash)
    $remote = & $Adb -s $Serial shell pm path $Package | Select-Object -First 1
    if ($LASTEXITCODE -ne 0 -or $remote -notmatch '^package:/data/app/[^\s]+/base\.apk$') { throw 'Installed APK path could not be verified' }
    $remote = $remote -replace '^package:', ''
    $actual = & $Adb -s $Serial shell sha256sum $remote
    if ($LASTEXITCODE -ne 0 -or ("$actual" -split '\s+')[0] -ine $ExpectedHash) { throw 'Installed APK hash differs from frozen input' }
}
