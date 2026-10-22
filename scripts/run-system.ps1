param(
  [switch]$SkipSmoke
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path
$RuntimeRoot = Join-Path $ProjectRoot "storage\runtime"
$LogRoot = Join-Path $ProjectRoot "storage\logs"
$StatePath = Join-Path $RuntimeRoot "system-state.json"

New-Item -ItemType Directory -Path $RuntimeRoot -Force | Out-Null
New-Item -ItemType Directory -Path $LogRoot -Force | Out-Null

function Write-Step($Message) {
  Write-Host "[run] $Message"
}

function Get-Listener($Port) {
  try {
    Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  } catch {
    $null
  }
}

function Wait-ForPort($Name, $Port, $TimeoutSeconds) {
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    $listener = Get-Listener $Port
    if ($listener) {
      Write-Step "$Name is listening on port $Port"
      return $true
    }
    Start-Sleep -Milliseconds 500
  }
  return $false
}

function Start-Component($Name, $ScriptName, $Port, $TimeoutSeconds) {
  $listener = Get-Listener $Port
  if ($listener) {
    Write-Step "$Name already running on port $Port (pid $($listener.OwningProcess))"
    return @{
      name = $Name
      port = $Port
      started = $false
      pid = $listener.OwningProcess
      stdout = $null
      stderr = $null
    }
  }

  $scriptPath = Join-Path $PSScriptRoot $ScriptName
  $stdout = Join-Path $LogRoot "$Name.out.log"
  $stderr = Join-Path $LogRoot "$Name.err.log"
  Write-Step "starting $Name"
  $process = Start-Process `
    -FilePath "powershell" `
    -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$scriptPath`"" `
    -WorkingDirectory $ProjectRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr `
    -PassThru

  if (-not (Wait-ForPort $Name $Port $TimeoutSeconds)) {
    throw "$Name did not start on port $Port within $TimeoutSeconds seconds. Check $stdout and $stderr"
  }

  return @{
    name = $Name
    port = $Port
    started = $true
    pid = $process.Id
    stdout = $stdout
    stderr = $stderr
  }
}

$components = @()
$components += Start-Component "ai-service" "run-ai.ps1" 8001 60
$components += Start-Component "backend" "run-backend.ps1" 8080 120
$components += Start-Component "frontend" "run-frontend.ps1" 5173 90

$state = @{
  projectRoot = $ProjectRoot
  startedAt = (Get-Date).ToString("o")
  components = $components
}
$state | ConvertTo-Json -Depth 5 | Set-Content -Path $StatePath -Encoding UTF8

Write-Host ""
Write-Host "System is ready."
Write-Host "Frontend: http://localhost:5173"
Write-Host "Backend:  http://localhost:8080"
Write-Host "AI:       http://localhost:8001"
Write-Host "Logs:     $LogRoot"
Write-Host "State:    $StatePath"

if (-not $SkipSmoke) {
  Write-Host ""
  Write-Step "running closed-loop smoke test"
  & (Join-Path $PSScriptRoot "smoke-system.ps1")
}
