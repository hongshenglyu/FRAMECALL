param(
  [string]$BackendUrl = "http://localhost:8080",
  [string]$AiUrl = "http://localhost:8001",
  [string]$Username = $env:FRAMECALL_SMOKE_USERNAME,
  [string]$Password = $env:FRAMECALL_SMOKE_PASSWORD,
  [string]$RefereeUsername = $env:FRAMECALL_SMOKE_REFEREE_USERNAME,
  [string]$RefereePassword = $env:FRAMECALL_SMOKE_REFEREE_PASSWORD,
  [string]$VideoPath = ""
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

foreach ($Credential in @($Username, $Password, $RefereeUsername, $RefereePassword)) {
  if ([string]::IsNullOrWhiteSpace($Credential)) {
    throw "Explicit local smoke credentials are required; no default accounts are available."
  }
}

function Write-Step($Message) {
  Write-Host "[smoke] $Message"
}

function Login($User, $Pass) {
  $body = @{ username = $User; password = $Pass } | ConvertTo-Json
  Invoke-RestMethod -Method Post -Uri "$BackendUrl/api/auth/login" -ContentType "application/json" -Body $body
}

function Require($Condition, $Message) {
  if (-not $Condition) {
    throw $Message
  }
}

Write-Step "Checking AI service"
$health = Invoke-RestMethod -Uri "$AiUrl/health"
Require ($health.status -eq "ok") "AI service is not healthy"

Write-Step "Logging in as admin"
$adminLogin = Login $Username $Password
$adminHeaders = @{ Authorization = "Bearer $($adminLogin.token)" }

if ([string]::IsNullOrWhiteSpace($VideoPath)) {
  $VideoPath = Join-Path $env:TEMP "football-ref-demo-sample.mp4"
  if (-not (Test-Path $VideoPath)) {
    Write-Step "Downloading short sample video"
    Invoke-WebRequest -Uri "https://samplelib.com/lib/preview/mp4/sample-5s.mp4" -OutFile $VideoPath
  }
}
Require (Test-Path $VideoPath) "Video file not found: $VideoPath"

Write-Step "Uploading video and creating async task"
$uploadJson = curl.exe -s -X POST "$BackendUrl/api/videos" `
  -H "Authorization: Bearer $($adminLogin.token)" `
  -F "file=@$VideoPath;type=video/mp4" `
  -F "title=Closed-loop smoke video" `
  -F "competition=Graduation Demo Cup" `
  -F "teams=Blue FC vs Red FC" `
  -F "sensitivity=BALANCED"
$upload = $uploadJson | ConvertFrom-Json
Require ($upload.video.id -gt 0) "Video upload did not return a video id"
Require ($upload.task.id -gt 0) "Upload did not return a task id"
$taskId = [int]$upload.task.id
Write-Step "Created task $taskId"

Write-Step "Polling task result"
$task = $null
for ($i = 0; $i -lt 30; $i++) {
  $task = Invoke-RestMethod -Method Get -Uri "$BackendUrl/api/tasks/$taskId" -Headers $adminHeaders
  Write-Step "poll=$i status=$($task.status) stage=$($task.stage) progress=$($task.progressPercent) events=$($task.events.Count)"
  if ($task.status -in @("SUCCEEDED", "FAILED", "CANCELLED")) {
    break
  }
  Start-Sleep -Milliseconds 500
}
Require ($task.status -eq "SUCCEEDED") "Task did not succeed: $($task.status) $($task.errorMessage)"
Require ($task.events.Count -eq 3) "Expected 3 mock events, got $($task.events.Count)"

$types = @($task.events | ForEach-Object { $_.eventType })
Require ($types -contains "FOUL") "Missing FOUL event"
Require ($types -contains "HANDBALL") "Missing HANDBALL event"
Require ($types -contains "OFFSIDE") "Missing OFFSIDE event"

Write-Step "Opening event detail"
$firstEventId = [int]$task.events[0].id
$eventDetail = Invoke-RestMethod -Method Get -Uri "$BackendUrl/api/events/$firstEventId" -Headers $adminHeaders
Require ($eventDetail.id -eq $firstEventId) "Event detail did not load"

Write-Step "Logging in as referee and saving review"
$refereeLogin = Login $RefereeUsername $RefereePassword
$refereeHeaders = @{ Authorization = "Bearer $($refereeLogin.token)" }
$reviewBody = @{
  result = "CONFIRMED"
  reasonCode = "SMOKE_TEST"
  note = "Confirmed during automated closed-loop smoke test."
} | ConvertTo-Json
$review = Invoke-RestMethod -Method Post -Uri "$BackendUrl/api/events/$firstEventId/review" -Headers $refereeHeaders -ContentType "application/json" -Body $reviewBody
Require ($review.result -eq "CONFIRMED") "Review was not saved"

Write-Step "Creating manual dispute event"
$manualBody = @{
  taskId = $taskId
  eventType = "FOUL"
  timestampMs = 61000
  summary = "Manual dispute event added during smoke test."
  involvedTrackIds = @(21, 22)
} | ConvertTo-Json
$manual = Invoke-RestMethod -Method Post -Uri "$BackendUrl/api/events/manual" -Headers $refereeHeaders -ContentType "application/json" -Body $manualBody
Require ($manual.id -gt 0) "Manual event was not created"

$taskAfterManual = Invoke-RestMethod -Method Get -Uri "$BackendUrl/api/tasks/$taskId" -Headers $adminHeaders
Require ($taskAfterManual.events.Count -eq 4) "Expected 4 events after manual entry, got $($taskAfterManual.events.Count)"

Write-Step "Exporting PDF report"
$pdfPath = Join-Path $env:TEMP "referee-review-report-$taskId.pdf"
Invoke-WebRequest -Method Get -Uri "$BackendUrl/api/tasks/$taskId/report" -Headers $adminHeaders -OutFile $pdfPath
$pdf = Get-Item $pdfPath
$bytes = [System.IO.File]::ReadAllBytes($pdfPath)
$signature = [System.Text.Encoding]::ASCII.GetString($bytes, 0, 4)
Require ($signature -eq "%PDF") "Report is not a PDF"

Write-Host ""
Write-Host "SMOKE PASS"
Write-Host "videoId=$($upload.video.id)"
Write-Host "taskId=$taskId"
Write-Host "mockEvents=3"
Write-Host "eventsAfterManual=$($taskAfterManual.events.Count)"
Write-Host "reviewId=$($review.id)"
Write-Host "report=$($pdf.FullName)"
Write-Host "reportBytes=$($pdf.Length)"
