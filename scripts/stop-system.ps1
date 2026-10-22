param(
  [switch]$Force
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path
$RuntimeRoot = Join-Path $ProjectRoot "storage\runtime"
$StatePath = Join-Path $RuntimeRoot "system-state.json"
$Ports = @(5173, 8080, 8001)

function Write-Step($Message) {
  Write-Host "[stop] $Message"
}

function Get-Listener($Port) {
  try {
    Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  } catch {
    $null
  }
}

function Get-ProcessInfo($ProcessId) {
  Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction SilentlyContinue
}

function Get-ChildProcessIds($ProcessId) {
  $children = Get-CimInstance Win32_Process -Filter "ParentProcessId = $ProcessId" -ErrorAction SilentlyContinue
  foreach ($child in $children) {
    Get-ChildProcessIds $child.ProcessId
    $child.ProcessId
  }
}

function Is-ProjectProcess($Info) {
  if (-not $Info) {
    return $false
  }
  $command = [string]$Info.CommandLine
  return $command.Contains($ProjectRoot) `
    -or $command.Contains("Z:\backend") `
    -or $command.Contains("Z:\tools") `
    -or ($command.Contains("uvicorn app.main:app") -and $command.Contains("--port 8001")) `
    -or ($command.Contains("vite") -and $command.Contains("5173"))
}

function Stop-Tree($ProcessId, $Reason) {
  if (-not $ProcessId) {
    return
  }
  $info = Get-ProcessInfo $ProcessId
  if (-not $info) {
    return
  }
  if (-not $Force -and -not (Is-ProjectProcess $info)) {
    Write-Step "skip pid $ProcessId ($($info.Name)); not recognized as this project. Use -Force to stop it."
    return
  }

  $ids = @(Get-ChildProcessIds $ProcessId) + @($ProcessId)
  foreach ($id in ($ids | Select-Object -Unique | Sort-Object -Descending)) {
    try {
      $process = Get-Process -Id $id -ErrorAction Stop
      Write-Step "stopping pid $id ($($process.ProcessName)) - $Reason"
      Stop-Process -Id $id -Force -ErrorAction Stop
    } catch {
      # Already exited.
    }
  }
}

if (Test-Path $StatePath) {
  $state = Get-Content $StatePath -Raw | ConvertFrom-Json
  foreach ($component in $state.components) {
    if ($component.started -eq $true) {
      Stop-Tree ([int]$component.pid) "recorded launcher for $($component.name)"
    }
  }
}

foreach ($port in $Ports) {
  $listener = Get-Listener $port
  if ($listener) {
    Stop-Tree ([int]$listener.OwningProcess) "listener on port $port"
  }
}

if (Test-Path $StatePath) {
  Remove-Item -LiteralPath $StatePath -Force
}

Write-Host "System stop complete."
