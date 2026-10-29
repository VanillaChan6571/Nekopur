param(
    [switch] $Export,
    [ValidateRange(1, 65535)] [int] $Port = 8765,
    [string] $Seed = '6571',
    [ValidateSet('neko', 'neko-mmo-tall')] [string] $Preset = 'neko',
    [ValidateRange(8, 1024)] [int] $Size = 256,
    [ValidateRange(1, 4096)] [int] $Step = 128,
    [int] $X = 0,
    [int] $Z = 0,
    [ValidateRange(1, 32)] [int] $Threads = 4,
    [ValidateSet('fast', 'detailed')] [string] $Mode = 'fast',
    [string] $Output = 'seed-previews',
    [string] $JavaHome = ''
)
$ErrorActionPreference = 'Stop'
$previewRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($JavaHome) -and (Test-Path -LiteralPath 'C:\Java\jdk-25.0.3\bin\java.exe')) {
    $JavaHome = 'C:\Java\jdk-25.0.3'
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) { $JavaHome = $env:JAVA_HOME }
if ([string]::IsNullOrWhiteSpace($JavaHome) -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Set JAVA_HOME to JDK 25, or pass -JavaHome <JDK 25 directory>.'
}
$javaVersion = & (Join-Path $JavaHome 'bin\java.exe') -version 2>&1 | Out-String
if ($javaVersion -notmatch 'version "25[.\"]') { throw 'This tool requires JDK 25. Pass -JavaHome <JDK 25 directory>.' }
$previousJavaHome = $env:JAVA_HOME
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
$previewProperties = @{seed=$Seed; preset=$Preset; size="$Size"; step="$Step"; x="$X"; z="$Z"; threads="$Threads"; mode=$Mode; output=$Output; port="$Port"}
$previousProperties = @{}
Push-Location -LiteralPath $previewRoot
try {
    $env:JAVA_HOME = $JavaHome
    # Nonexistent absolute socket directory makes Windows Java use TCP for Gradle.
    $socketPath = Join-Path ([System.IO.Path]::GetTempPath()) ('neko-preview-socket-' + [guid]::NewGuid().ToString('N'))
    $env:JAVA_TOOL_OPTIONS = "$previousJavaOptions -Djdk.net.unixdomain.tmpdir=`"$socketPath`"".Trim()
    # Pass arbitrary seed text through the environment, never through cmd.exe syntax.
    foreach ($entry in $previewProperties.GetEnumerator()) {
        $propertyName = 'ORG_GRADLE_PROJECT_preview.' + $entry.Key
        $previousProperties[$propertyName] = [Environment]::GetEnvironmentVariable($propertyName, 'Process')
        [Environment]::SetEnvironmentVariable($propertyName, $entry.Value, 'Process')
    }
    $previewTask = if ($Export) { ':nekopur-server:previewSeed' } else { ':nekopur-server:seedViewer' }
    if (!$Export) { Write-Output "Starting the live viewer at http://127.0.0.1:$Port . Wait for the ready message, then open that address. Keep this terminal running; Ctrl+C stops the viewer." }
    & .\gradlew.bat $previewTask '--no-daemon'
    if ($LASTEXITCODE -ne 0) { throw "Seed preview failed (exit $LASTEXITCODE)." }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    foreach ($entry in $previousProperties.GetEnumerator()) {
        $restoredValue = if ($null -eq $entry.Value) { [NullString]::Value } else { $entry.Value }
        [Environment]::SetEnvironmentVariable($entry.Key, $restoredValue, 'Process')
    }
}
