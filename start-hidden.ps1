$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ConfigFile = Join-Path $Root 'config.env'
$BackendDir = Join-Path $Root 'atlas-backend'
$FrontendDir = Join-Path $Root 'atlas-frontend'
$StartLog = Join-Path $Root 'atlas-start.log'
$BackendLog = Join-Path $Root 'atlas-backend.log'
$FrontendLog = Join-Path $Root 'atlas-frontend.log'

function Write-AtlasLog {
    param([string]$Path, [string]$Message)
    Add-Content -LiteralPath $Path -Encoding UTF8 -Value ("{0} {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message)
}

function Import-AtlasConfig {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $index = $trimmed.IndexOf('=')
        if ($index -le 0) { continue }
        $name = $trimmed.Substring(0, $index).Trim()
        $value = $trimmed.Substring($index + 1)
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

function Get-EnvOrDefault {
    param([string]$Name, [string]$Default)
    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value
}

function Test-PortListening {
    param([int]$Port)
    $lines = netstat -ano | Select-String -SimpleMatch ":$Port"
    foreach ($line in $lines) {
        if ($line.Line -match '\sLISTENING\s') { return $true }
    }
    return $false
}

function Start-HiddenCmd {
    param(
        [string]$WorkingDirectory,
        [string]$Command,
        [string]$LogPath
    )
    $cmdLine = 'cd /d "{0}" && {1} >> "{2}" 2>&1' -f $WorkingDirectory, $Command, $LogPath
    Start-Process -FilePath "$env:ComSpec" -ArgumentList @('/d', '/s', '/c', $cmdLine) -WorkingDirectory $WorkingDirectory -WindowStyle Hidden
}

Import-AtlasConfig $ConfigFile

$BackendPort = [int](Get-EnvOrDefault 'ATLAS_SERVER_PORT' '8080')
$FrontendPort = [int](Get-EnvOrDefault 'VITE_DEV_PORT' '5173')
$ViteHost = Get-EnvOrDefault 'VITE_DEV_HOST' '0.0.0.0'
$ApiOrigin = Get-EnvOrDefault 'VITE_API_ORIGIN' "http://localhost:$BackendPort"

$env:ATLAS_SERVER_PORT = "$BackendPort"
$env:VITE_DEV_PORT = "$FrontendPort"
$env:VITE_DEV_HOST = $ViteHost
$env:VITE_API_ORIGIN = $ApiOrigin

$MavenCmd = Get-EnvOrDefault 'MAVEN_CMD' ''
if ([string]::IsNullOrWhiteSpace($MavenCmd)) {
    $BundledMaven = Join-Path $Root '.tools\apache-maven-3.9.16\bin\mvn.cmd'
    if (Test-Path -LiteralPath $BundledMaven) {
        $MavenCmd = $BundledMaven
    } else {
        $MavenCmd = Join-Path $BackendDir 'mvnw.cmd'
    }
}

Write-AtlasLog $StartLog 'Atlas hidden start requested.'

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-AtlasLog $StartLog 'Java was not found in PATH.'
    exit 1
}
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-AtlasLog $StartLog 'Node.js was not found in PATH.'
    exit 1
}
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
    Write-AtlasLog $StartLog 'npm.cmd was not found in PATH.'
    exit 1
}

if (Test-PortListening $BackendPort) {
    Write-AtlasLog $StartLog "Backend port $BackendPort is already listening; skipped."
} else {
    Set-Content -LiteralPath $BackendLog -Encoding UTF8 -Value 'Starting Atlas backend'
    Start-HiddenCmd -WorkingDirectory $BackendDir -Command ('call "{0}" spring-boot:run -Dspring-boot.run.profiles=local' -f $MavenCmd) -LogPath $BackendLog
    Write-AtlasLog $StartLog "Backend start command sent on port $BackendPort."
}

if (Test-PortListening $FrontendPort) {
    Write-AtlasLog $StartLog "Frontend port $FrontendPort is already listening; skipped."
} else {
    Set-Content -LiteralPath $FrontendLog -Encoding UTF8 -Value 'Starting Atlas frontend'
    Start-HiddenCmd -WorkingDirectory $FrontendDir -Command ('call npm.cmd run dev -- --host {0} --port {1}' -f $ViteHost, $FrontendPort) -LogPath $FrontendLog
    Write-AtlasLog $StartLog "Frontend start command sent on port $FrontendPort."
}
