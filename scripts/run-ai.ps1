Set-Location "$PSScriptRoot\..\ai-service"
if (-not (Test-Path ".venv")) {
  python -m venv .venv
}
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python -m uvicorn app.main:app --reload --port 8001

