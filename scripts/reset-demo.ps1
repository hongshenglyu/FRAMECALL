param(
  [switch]$Force
)

$ErrorActionPreference = "Stop"

$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path
$StorageRoot = (Resolve-Path (Join-Path $ProjectRoot "storage")).Path
$Targets = @(
  "uploads",
  "tasks",
  "annotations",
  "dev-db"
) | ForEach-Object { Join-Path $StorageRoot $_ }

try {
  $backendListener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
} catch {
  $backendListener = $null
}

if ($backendListener -and -not $Force) {
  throw "Backend appears to be running on port 8080. Stop it first, or rerun with -Force if you only want a best-effort cleanup."
}

foreach ($target in $Targets) {
  $fullTarget = [System.IO.Path]::GetFullPath($target)
  if (-not $fullTarget.StartsWith($StorageRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to clean path outside storage root: $fullTarget"
  }
}

foreach ($target in $Targets) {
  if (Test-Path -LiteralPath $target) {
    Write-Host "[reset] removing $target"
    Remove-Item -LiteralPath $target -Recurse -Force
  }
}

foreach ($target in $Targets) {
  New-Item -ItemType Directory -Path $target -Force | Out-Null
}

Write-Host "Demo storage reset complete."
Write-Host "Cleared: uploads, tasks, annotations, dev-db"
