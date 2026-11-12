# Build the independent Android app. Release keys remain outside the public repository.
param([ValidateSet('debug','profile','release')][string]$Mode = 'profile', [string]$Round = 'R41')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$flutter = Join-Path $projectRoot 'work/toolchains/flutter/bin/flutter.bat'
if (-not (Test-Path -LiteralPath $flutter)) { $flutter = 'flutter' }
foreach ($item in @{ANDROID_HOME='android-sdk';ANDROID_USER_HOME='android-user';GRADLE_USER_HOME='gradle';PUB_CACHE='pub-cache'}.GetEnumerator()) {
    $location = Join-Path $projectRoot "work/toolchains/$($item.Value)"
    if (Test-Path -LiteralPath $location) { Set-Item -Path "Env:$($item.Key)" -Value $location }
}
if ($Mode -eq 'release' -and -not $env:XMU_ANDROID_KEY_PROPERTIES) { throw 'Set XMU_ANDROID_KEY_PROPERTIES to your external signing properties file.' }
$output = Join-Path $projectRoot "outputs/apk/$Round"
New-Item -ItemType Directory -Force -Path $output | Out-Null
Push-Location (Join-Path $projectRoot 'apps/mobile')
try {
    # Windows PowerShell treats Gradle's informational stderr as NativeCommandError.
    $ErrorActionPreference = 'Continue'
    & $flutter build apk "--$Mode" --flavor production --target-platform android-arm64 --split-per-abi
    $buildExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($buildExit -ne 0) { throw 'Android build failed' }
    $source = "build/app/outputs/flutter-apk/app-arm64-v8a-production-$Mode.apk"
    $target = Join-Path $output "xmu-booker-0.2.0-arm64-$Mode.apk"
    Copy-Item -LiteralPath $source -Destination $target
    Write-Output $target
    Get-FileHash -LiteralPath $target -Algorithm SHA256
} finally { Pop-Location }
