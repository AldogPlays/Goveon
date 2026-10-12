param(
    [Parameter(Mandatory = $true)][string]$KeyStore,
    [Parameter(Mandatory = $true)][string]$KeyAlias,
    [string]$Version = '1.0.0'
)
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+(?:-[a-zA-Z0-9.]+)?$') { throw 'Use a semantic release version.' }
if (-not $env:GOVEON_SIGNING_STORE_PASSWORD -or -not $env:GOVEON_SIGNING_KEY_PASSWORD) {
    throw 'Set signing passwords in GOVEON_SIGNING_STORE_PASSWORD and GOVEON_SIGNING_KEY_PASSWORD for this process only.'
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$keyPath = (Resolve-Path -LiteralPath $KeyStore -ErrorAction Stop).Path
$env:JAVA_HOME = Join-Path $projectRoot '.tools/jdk/jdk-17.0.20.1+1'
$buildTools = Join-Path $projectRoot '.tools/android-sdk/build-tools/35.0.0'
$unsigned = Join-Path $projectRoot 'app/build/outputs/apk/release/app-release-unsigned.apk'
$aligned = Join-Path $projectRoot '.tools/goveon-release-aligned.apk'
$artifact = Join-Path $projectRoot "artifacts/goveon-$Version.apk"
if (-not (Test-Path -LiteralPath $unsigned)) { throw 'Build :app:assembleRelease before packaging.' }
$metadata = Get-Content -LiteralPath (Join-Path $projectRoot 'app/build/outputs/apk/release/output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.variantName -ne 'release' -or $metadata.applicationId -ne 'com.goveon.app' -or $metadata.elements.Count -ne 1 -or $metadata.elements[0].versionName -ne $Version) {
    throw 'The built release variant/version does not match the requested artifact.'
}
& (Join-Path $buildTools 'zipalign.exe') -f -P 16 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'Release APK alignment failed.' }
try {
    & (Join-Path $buildTools 'apksigner.bat') sign --ks $keyPath --ks-key-alias $KeyAlias `
        --ks-pass env:GOVEON_SIGNING_STORE_PASSWORD --key-pass env:GOVEON_SIGNING_KEY_PASSWORD --out $artifact $aligned
    if ($LASTEXITCODE -ne 0) { throw 'Release APK signing failed.' }
    & (Join-Path $buildTools 'apksigner.bat') verify --verbose $artifact
    if ($LASTEXITCODE -ne 0) { throw 'Release signature verification failed.' }
    & (Join-Path $buildTools 'zipalign.exe') -c -P 16 4 $artifact
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment verification failed.' }
    $checksum = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$artifact.sha256", "$checksum  $([IO.Path]::GetFileName($artifact))`n", [Text.UTF8Encoding]::new($false))
    Write-Output "Packaged goveon-$Version.apk with verified signature/alignment and SHA-256 checksum."
} finally {
    # Signing credentials are transient and never written to project files or command arguments.
    Remove-Item Env:GOVEON_SIGNING_STORE_PASSWORD, Env:GOVEON_SIGNING_KEY_PASSWORD -ErrorAction SilentlyContinue
}
