"""V15 public project data; no viewer or native Fusion dependencies."""
from pathlib import Path
import hashlib, json

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'delivery'
TOOLS = ROOT.parents[2] / 'tools'

def read(p):
    return json.loads(Path(p).read_text())

def save(p, value):
    p = Path(p); p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')

def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()
