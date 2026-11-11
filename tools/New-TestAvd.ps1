param(
    [Parameter(Mandatory=$true)][ValidateSet(26,28,33,36)][int]$Api,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [ValidatePattern('^[A-Za-z0-9_-]{0,24}$')][string]$Purpose = ''
)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$artifactRoot = Join-Path $projectPath 'artifacts\acceptance-0.9.0-20261110'
$env:ANDROID_AVD_HOME = Join-Path $artifactRoot 'avds'
$env:ANDROID_USER_HOME = Join-Path $artifactRoot 'android-user'
New-Item -ItemType Directory -Path $env:ANDROID_AVD_HOME,$env:ANDROID_USER_HOME -Force | Out-Null
$suffix = if ($Purpose) { "-$Purpose" } else { "" }
$name = "MuseumReader_API_$Api$suffix"
$target = Join-Path $env:ANDROID_AVD_HOME "$name.avd"
if (Test-Path -LiteralPath $target) { throw 'Test AVD already exists; it will not be overwritten' }
$image = Join-Path $SdkRoot "system-images\android-$Api\google_apis\x86_64"
if (-not (Test-Path -LiteralPath (Join-Path $image 'package.xml'))) { throw 'Verified SDK image is not registered' }
$log = Join-Path $artifactRoot "avd-api-$Api$suffix-create.log"
'no' | & (Join-Path $SdkRoot 'cmdline-tools\latest\bin\avdmanager.bat') create avd --name $name --package "system-images;android-$Api;google_apis;x86_64" --device pixel_2 --path $target *> $log
if ($LASTEXITCODE -ne 0) { Get-Content $log -Tail 30; throw 'AVD creation failed' }
$config = Join-Path $target 'config.ini'
$text = Get-Content -LiteralPath $config -Raw
$text = [regex]::Replace($text, '(?m)^hw.ramSize=.*$', 'hw.ramSize=2048')
[System.IO.File]::WriteAllText($config, $text, [System.Text.UTF8Encoding]::new($false))
[ordered]@{api=$Api;name=$name;avdHome=$env:ANDROID_AVD_HOME;path=$target;sdkRoot=$SdkRoot;readOnlyTests=$true} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $artifactRoot "avd-api-$Api$suffix.json") -Encoding utf8
Write-Output "Created project-owned test AVD: $name"
