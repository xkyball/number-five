"""Build auditable training rows and rule-vs-human evaluation from sessions."""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path


def _lines(path: Path):
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line:
                yield json.loads(line)


def iter_rows(storage_root: Path):
    """Yield one row per classified track, preserving session/segment provenance."""
    for session in sorted((storage_root / "sessions").glob("*")):
        labels = {}
        for label in _lines(session / "derived" / "labels.jsonl"):
            labels[(label["ping_id"], label["track_id"])] = label
        for detection_path in sorted((session / "derived").glob("*/detections.jsonl")):
            segment = detection_path.parent.name
            for ping in _lines(detection_path):
                for track in ping.get("tracks", []):
                    key = (ping["ping_id"], track["track_id"])
                    human = labels.get(key)
                    yield {
                        "schema": "no5.sonard-dataset-row/1",
                        "session_id": session.name, "segment_id": segment,
                        "ping_id": ping["ping_id"], "time_utc_ns": ping.get("time_utc_ns"),
                        "valid_tx": ping["valid_tx"], "policy": ping["policy"],
                        "context": ping.get("acquisition_context"),
                        "track": track, "rule_label": track["classification"],
                        "human_label": None if human is None else human["label"],
                        "label_record": human,
                    }


def build(storage_root: Path, output: Path) -> dict:
    rows = list(iter_rows(storage_root))
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, sort_keys=True, separators=(",", ":")) + "\\n")
    return {"schema": "no5.sonard-dataset/1", "rows": len(rows), "output": str(output),
            "rule_classes": dict(sorted(Counter(row["rule_label"] for row in rows).items())),
            "human_labels": sum(row["human_label"] is not None for row in rows)}


def evaluate(storage_root: Path) -> dict:
    rows = [row for row in iter_rows(storage_root) if row["human_label"] is not None]
    matrix = Counter((row["human_label"], row["rule_label"]) for row in rows)
    correct = sum(count for (actual, predicted), count in matrix.items() if actual == predicted)
    return {
        "schema": "no5.sonard-rules-evaluation/1", "labeled_rows": len(rows),
        "agreement": None if not rows else correct / len(rows),
        "confusion_matrix": [{"human_label": actual, "rule_label": predicted, "count": count}
                             for (actual, predicted), count in sorted(matrix.items())],
        "note": "Rule agreement is not biological accuracy. It becomes meaningful only after review labels.",
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("build", "evaluate"):
        command = sub.add_parser(name)
        command.add_argument("--storage-root", required=True, type=Path)
        if name == "build":
            command.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    result = build(args.storage_root, args.output) if args.command == "build" else evaluate(args.storage_root)
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
