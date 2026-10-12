param(
    [ValidateSet('environment', 'build', 'adb', 'rea')]
    [string]$Action = 'environment',
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ToolArguments
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = Join-Path $projectRoot '.tools'
$env:JAVA_HOME = Join-Path $toolsRoot 'jdk/jdk-17.0.20.1+1'
$env:ANDROID_HOME = Join-Path $toolsRoot 'android-sdk'
$env:ANDROID_USER_HOME = Join-Path $toolsRoot 'android-user'
$env:GRADLE_USER_HOME = Join-Path $toolsRoot 'gradle-user'
$env:REA_JADX_MCP_JAR = Join-Path $toolsRoot 'jadx-headless-mcp-0.7.1-all.jar'
# This 184 MiB APK exceeds smaller analysis heaps; run one headless worker at a time.
$env:REA_JADX_HEAP_MIB = '8192'
$env:Path = "$env:JAVA_HOME/bin;$env:ANDROID_HOME/platform-tools;$env:Path"

foreach ($directory in @($env:JAVA_HOME, $env:ANDROID_HOME)) {
    if (-not (Test-Path -LiteralPath $directory)) {
        throw "Missing local toolchain: $directory. See scripts/toolchain/README.md."
    }
}
foreach ($directory in @($env:ANDROID_USER_HOME, $env:GRADLE_USER_HOME)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

switch ($Action) {
    'environment' {
        Write-Output "JAVA_HOME=$env:JAVA_HOME"
        Write-Output "ANDROID_HOME=$env:ANDROID_HOME"
        Write-Output "GRADLE_USER_HOME=$env:GRADLE_USER_HOME"
        Write-Output "REA_JADX_MCP_JAR=$env:REA_JADX_MCP_JAR"
    }
    'build' {
        if (-not $ToolArguments) { $ToolArguments = @(':app:assembleDebug', ':app:testDebugUnitTest') }
        Push-Location $projectRoot
        try { & (Join-Path $projectRoot 'gradlew.bat') --no-daemon --console=plain --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' @ToolArguments }
        finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    }
    'adb' {
        if (-not $ToolArguments) { $ToolArguments = @('version') }
        & (Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe') @ToolArguments
        if ($LASTEXITCODE -ne 0) { throw "ADB failed with exit code $LASTEXITCODE" }
    }
    'rea' {
        # Machine-specific launcher paths stay in environment variables or ignored local config.
        $reaLocalConfig = Join-Path $toolsRoot 'rea-local.json'
        $reaConfig = if (Test-Path -LiteralPath $reaLocalConfig) { Get-Content -LiteralPath $reaLocalConfig -Raw | ConvertFrom-Json } else { $null }
        $reaLauncher = if ($env:REA_LAUNCHER) { $env:REA_LAUNCHER } else { $reaConfig.launcher }
        if ($reaLauncher) {
            $reaNode = if ($env:REA_NODE) { $env:REA_NODE } elseif ($reaConfig.node) { $reaConfig.node } else { (Get-Command node -ErrorAction Stop).Source }
            if (-not (Test-Path -LiteralPath $reaNode) -or -not (Test-Path -LiteralPath $reaLauncher)) { throw 'Configured REA launcher is unavailable.' }
            & $reaNode $reaLauncher @ToolArguments
        } else {
            $reaCommand = Get-Command rea -ErrorAction SilentlyContinue
            if (-not $reaCommand) { throw 'Install REA 6.4.0 or set REA_LAUNCHER and optionally REA_NODE. Keep local paths in ignored .tools/rea-local.json.' }
            & $reaCommand.Source @ToolArguments
        }
        if ($LASTEXITCODE -ne 0) { throw "REA failed with exit code $LASTEXITCODE" }
    }
}
