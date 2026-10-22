from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ai-service"))

from app.analyzer import run_analysis
from app.schemas import AnalysisRequest


def main() -> None:
    output_dir = ROOT / "storage" / "tasks" / "smoke-ai"
    output_dir.mkdir(parents=True, exist_ok=True)
    request = AnalysisRequest(
        taskId=1,
        videoPath=str(ROOT / "storage" / "demo-placeholder.mp4"),
        outputDir=str(output_dir),
        sensitivity="BALANCED",
        enabledEvents=["FOUL", "HANDBALL", "OFFSIDE"],
    )
    result = run_analysis(request)
    print(result.model_dump_json(by_alias=True, indent=2))


if __name__ == "__main__":
    main()

