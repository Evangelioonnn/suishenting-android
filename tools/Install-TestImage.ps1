param(
    [Parameter(Mandatory=$true)][ValidateSet(28,33)][int]$Api,
    [string]$SdkRoot = $env:ANDROID_HOME
)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$artifactRoot = Join-Path $projectPath 'artifacts\acceptance-0.9.0-20261110'
$metadataPath = Join-Path $artifactRoot 'google-api-images.xml'
[xml]$metadata = Get-Content -LiteralPath $metadataPath -Raw
$packageId = "system-images;android-$Api;google_apis;x86_64"
$package = $metadata.SelectSingleNode("//*[local-name()='remotePackage' and @path='$packageId']")
if ($null -eq $package) { throw "Official image metadata not found: $packageId" }
$complete = $package.SelectSingleNode("./*[local-name()='archives']/*[local-name()='archive']/*[local-name()='complete']")
$archiveName = $complete.SelectSingleNode("./*[local-name()='url']").InnerText
if ($archiveName -notmatch "^x86_64-$($Api)_r\d+\.zip$") { throw 'Unexpected archive name' }
$expectedSize = [long]$complete.SelectSingleNode("./*[local-name()='size']").InnerText
$expectedSha1 = $complete.SelectSingleNode("./*[local-name()='checksum']").InnerText
$archivePath = Join-Path $artifactRoot $archiveName
Write-Output "Downloading official API $Api image ($expectedSize bytes)"
& curl.exe --silent --show-error --fail --location --retry 2 --connect-timeout 20 --max-time 1800 --continue-at - --output $archivePath "https://dl.google.com/android/repository/sys-img/google_apis/$archiveName"
if ($LASTEXITCODE -ne 0) { throw "Image download failed for API $Api" }
if ((Get-Item -LiteralPath $archivePath).Length -ne $expectedSize) { throw 'Archive size mismatch' }
if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA1).Hash -ne $expectedSha1) { throw 'Archive checksum mismatch' }
$stage = Join-Path $artifactRoot ("sdk-stage-" + $Api + "-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $stage | Out-Null
$stageFull = [System.IO.Path]::GetFullPath($stage)
$imagesRoot = [System.IO.Path]::GetFullPath((Join-Path $SdkRoot 'system-images'))
$target = [System.IO.Path]::GetFullPath((Join-Path $imagesRoot "android-$Api\google_apis\x86_64"))
if (-not $stageFull.StartsWith([System.IO.Path]::GetFullPath($artifactRoot) + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe staging directory' }
if (-not $target.StartsWith($imagesRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe image destination' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
try {
    foreach ($entry in $zip.Entries) {
        $expanded = [System.IO.Path]::GetFullPath((Join-Path $stageFull $entry.FullName))
        if (-not $expanded.StartsWith($stageFull + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe archive entry' }
    }
} finally { $zip.Dispose() }
[System.IO.Compression.ZipFile]::ExtractToDirectory($archivePath, $stageFull)
$source = [System.IO.Path]::GetFullPath((Join-Path $stageFull 'x86_64'))
if (-not $source.StartsWith($stageFull + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe image source' }
if (-not (Test-Path -LiteralPath (Join-Path $source 'source.properties'))) { throw 'Image source properties missing' }
if (Test-Path -LiteralPath $target) {
    $existing = @(Get-ChildItem -LiteralPath $target -Force)
    if ($existing.Count -ne 1 -or $existing[0].Name -ne '.installer') { throw 'Existing SDK image is preserved; destination is not empty' }
    $quarantine = Join-Path $artifactRoot ("sdk-interrupted-" + $Api + "-" + [guid]::NewGuid())
    $quarantineFull = [System.IO.Path]::GetFullPath($quarantine)
    if (-not $quarantineFull.StartsWith([System.IO.Path]::GetFullPath($artifactRoot) + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe quarantine path' }
    Move-Item -LiteralPath $target -Destination $quarantineFull
}
New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
Move-Item -LiteralPath $source -Destination $target
$metadataScript = @'
import copy, sys
import xml.etree.ElementTree as ET
metadata, target, package_id = sys.argv[1:]
root = ET.parse(metadata).getroot()
package = next(e for e in root if e.tag.split('}')[-1]=='remotePackage' and e.attrib.get('path')==package_id)
local = copy.deepcopy(package)
local.tag = 'localPackage'
local.set('obsolete','false')
for child in list(local):
    if child.tag.split('}')[-1] in ('archives','channelRef'):
        local.remove(child)
ns = 'http://schemas.android.com/repository/android/common/02'
ET.register_namespace('repo',ns)
ET.register_namespace('xsi','http://www.w3.org/2001/XMLSchema-instance')
out = ET.Element('{'+ns+'}repository', {'xmlns:sys-img':'http://schemas.android.com/sdk/android/repo/sys-img2/03'})
license_id = local.find('uses-license').attrib['ref']
license_node = next(e for e in root if e.tag.split('}')[-1]=='license' and e.attrib.get('id')==license_id)
out.append(copy.deepcopy(license_node))
out.append(local)
ET.indent(out)
ET.ElementTree(out).write(target,encoding='utf-8',xml_declaration=True)
'@
$metadataScript | python - $metadataPath (Join-Path $target 'package.xml') $packageId
if ($LASTEXITCODE -ne 0) { throw 'Unable to write SDK package registration' }
[ordered]@{
    api = $Api
    package = $packageId
    source = "https://dl.google.com/android/repository/sys-img/google_apis/$archiveName"
    size = $expectedSize
    sha1 = $expectedSha1
    installedPath = $target
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $artifactRoot "sdk-api-$Api.json") -Encoding utf8
Write-Output "Installed official API $Api test image: $target"
