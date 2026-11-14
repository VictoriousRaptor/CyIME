# Build from the repository root, archive obsolete APKs, then print verified output paths.
[CmdletBinding()]
param(
    [ValidateSet('Debug', 'Release')][string]$BuildType = 'Release',
    [string[]]$GradleArguments = @(),
    [ValidateSet('arm64-v8a', 'armeabi-v7a')][string]$ReleaseAbi = 'arm64-v8a',
    [switch]$BundleModels,
    [switch]$RunLayoutGate,
    [int]$AdbPort = 5037,
    [string]$Serial = '',
    [string]$BuildDirectory = 'app/build'
)
$ErrorActionPreference = 'Stop'
function Get-ApkSha256([string]$LiteralPath) {
    # Windows PowerShell inherited from pwsh may not discover Utility's Get-FileHash.
    # Use the same SHA-256 algorithm without depending on module auto-loading.
    $stream = [IO.File]::OpenRead($LiteralPath)
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $hasher.Dispose(); $stream.Dispose() }
}
$repoRoot = Split-Path $PSScriptRoot -Parent
Push-Location $repoRoot
try {
    $config = Get-Content 'app/build.gradle.kts' -Raw
    $version = [regex]::Match($config, 'versionName = "([^"]+)"').Groups[1].Value
    $versionCode = [int][regex]::Match($config, 'versionCode = (\d+)').Groups[1].Value
    if (-not $version) { throw 'Cannot determine app version.' }
    $kind = $BuildType.ToLowerInvariant()
    $buildRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot $BuildDirectory))
    if (-not $buildRoot.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Build directory must be inside the repository.' }
    $apkRoot = Join-Path $buildRoot 'outputs/apk'
    $archive = Join-Path $apkRoot ('archive/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    foreach ($variant in @('debug', 'release')) {
        $output = Join-Path $apkRoot $variant
        if (-not (Test-Path -LiteralPath $output)) { continue }
        $old = @(Get-ChildItem -LiteralPath $output -File -Filter '*.apk' | Where-Object {
            $variant -eq $kind -or -not $_.Name.StartsWith("CyIME-$version-")
        })
        foreach ($file in $old) {
            # Only generated files directly in these two output folders may be moved.
            if ($file.DirectoryName -ne $output) { throw 'Unexpected APK path.' }
            $destination = [IO.Path]::GetFullPath((Join-Path $archive $variant))
            if (-not $destination.StartsWith($apkRoot + [IO.Path]::DirectorySeparatorChar)) { throw 'Unsafe archive path.' }
            New-Item -ItemType Directory -Force -Path $destination | Out-Null
            Move-Item -LiteralPath $file.FullName -Destination $destination
        }
        if ($old.Count -gt 0 -and @(Get-ChildItem -LiteralPath $output -File -Filter '*.apk').Count -eq 0) {
            foreach ($name in @('output-metadata.json', 'latest.json')) {
                $metadata = Join-Path $output $name
                if (Test-Path -LiteralPath $metadata) { Move-Item -LiteralPath $metadata -Destination (Join-Path $archive $variant) }
            }
        }
    }
    $editionArguments = @("-PbundleModels=$($BundleModels.IsPresent.ToString().ToLowerInvariant())")
    if ($ReleaseAbi -ne 'arm64-v8a') {
        $editionArguments += @('-I', (Join-Path $PSScriptRoot 'phone-release-abi.gradle'), "-PcyimeReleaseAbi=$ReleaseAbi")
    }
    $buildTasks = @(':app:assemble' + $BuildType)
    if ($RunLayoutGate) { $buildTasks += @(':app:assembleDebug', ':app:assembleDebugAndroidTest') }
    & ./gradlew.bat @buildTasks --no-daemon @editionArguments @GradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Build failed. Previous packages remain in $archive" }
    $output = Join-Path $apkRoot $kind
    # AGP can leave assemble outputs at its APK artifact location (intermediates).
    # Resolve the generated locator and copy verified artifacts to our delivery folder.
    if (-not (Test-Path -LiteralPath (Join-Path $output 'output-metadata.json'))) {
        $locator = Join-Path $buildRoot "intermediates/apk_ide_redirect_file/$kind/create${BuildType}ApkListingFileRedirect/redirect.txt"
        $listing = @(Get-Content -LiteralPath $locator | Where-Object { $_.StartsWith('listingFile=') })
        if ($listing.Count -ne 1) { throw 'Cannot resolve the generated APK metadata locator.' }
        $metadataPath = [IO.Path]::GetFullPath((Join-Path (Split-Path $locator -Parent) $listing[0].Substring(12)))
        if (-not $metadataPath.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'APK metadata is outside the build directory.' }
        $generated = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
        if ($generated.applicationId -ne 'com.cyletix.cyime' -or $generated.variantName -ne $kind) { throw 'Unexpected generated APK identity.' }
        $artifactRoot = Split-Path $metadataPath -Parent
        New-Item -ItemType Directory -Force -Path $output | Out-Null
        foreach ($element in $generated.elements) {
            if ($element.versionName -ne $version -or $element.versionCode -ne $versionCode) { throw 'Generated APK version mismatch.' }
            $artifact = [IO.Path]::GetFullPath((Join-Path $artifactRoot $element.outputFile))
            if ([IO.Path]::GetDirectoryName($artifact) -ne $artifactRoot) { throw 'Unexpected generated APK path.' }
            Copy-Item -LiteralPath $artifact -Destination $output
        }
        $profiles = Join-Path $artifactRoot 'baselineProfiles'
        if (Test-Path -LiteralPath $profiles) { Copy-Item -LiteralPath $profiles -Destination $output -Recurse -Force }
        Copy-Item -LiteralPath $metadataPath -Destination (Join-Path $output 'output-metadata.json')
    }
    $metadata = Get-Content (Join-Path $output 'output-metadata.json') -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ne 'com.cyletix.cyime') { throw 'Unexpected application ID.' }
    $deliveryElements = if ($BuildType -eq "Release") {
        @($metadata.elements | Where-Object { @($_.filters | Where-Object { $_.filterType -eq "ABI" -and $_.value -eq $ReleaseAbi }).Count -eq 1 })
    } else { @($metadata.elements) }
    if ($deliveryElements.Count -eq 0) { throw "No APK matches the delivery architecture." }
    $files = foreach ($element in $deliveryElements) {
        if ($element.versionName -ne $version -or $element.versionCode -ne $versionCode) { throw 'APK metadata version mismatch.' }
        $apk = Get-Item -LiteralPath (Join-Path $output $element.outputFile)
        if ($apk.DirectoryName -ne $output) { throw 'Unexpected APK output location.' }
        [pscustomobject]@{ path=$apk.FullName; bytes=$apk.Length; sha256=(Get-ApkSha256 -LiteralPath $apk.FullName) }
    }
    # The full layout gate is paused by project policy; opt in explicitly.
    $layoutGate = 'not-run'
    if ($RunLayoutGate) { try {
        & "$PSScriptRoot/verify-layout.ps1" -AdbPort $AdbPort -Serial $Serial -BuildDirectory $BuildDirectory
        $layoutGate = 'passed'
    } catch {
        $rejected = [IO.Path]::GetFullPath((Join-Path $apkRoot ('rejected/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))))
        if (-not $rejected.StartsWith($apkRoot + [IO.Path]::DirectorySeparatorChar)) { throw 'Unsafe rejected APK path.' }
        New-Item -ItemType Directory -Force -Path $rejected | Out-Null
        foreach ($item in $files) {
            if ([IO.Path]::GetDirectoryName($item.path) -ne $output) { throw 'Unexpected rejected APK path.' }
            Move-Item -LiteralPath $item.path -Destination $rejected
        }
        throw "Layout gate failed; APKs isolated in $rejected. $($_.Exception.Message)"
    } }
    [pscustomobject]@{ version=$version; versionCode=$versionCode; buildType=$kind; bundledModels=$BundleModels.IsPresent; files=@($files); layoutGate=$layoutGate; sourceCommit=(git rev-parse HEAD).Trim() } |
        ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $output 'latest.json')
    # Keep both editions when the next build replaces the shared Gradle output folder.
    $edition = if ($BundleModels) { 'full' } else { 'standard' }
    $delivery = Join-Path $apkRoot "editions/$version/$edition/$kind"
    if ($BuildType -eq "Release" -and $ReleaseAbi -ne "arm64-v8a") { $delivery = Join-Path $delivery $ReleaseAbi }
    New-Item -ItemType Directory -Force -Path $delivery | Out-Null
    foreach ($item in $files) { Copy-Item -LiteralPath $item.path -Destination $delivery }
    $savedReceipt = Get-Content (Join-Path $output 'latest.json') -Raw | ConvertFrom-Json
    foreach ($item in $savedReceipt.files) { $item.path = Join-Path $delivery ([IO.Path]::GetFileName($item.path)) }
    $savedReceipt | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $delivery 'latest.json')
    if ($RunLayoutGate) { Copy-Item -LiteralPath (Join-Path $buildRoot 'reports/layout-gate/passed.json') -Destination (Join-Path $delivery 'layout-gate.json') }
    Write-Host "Built CyIME $version ($BuildType). Old packages: $archive"
    $files | Format-Table path, bytes -AutoSize
} finally { Pop-Location }
