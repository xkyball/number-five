"""R3 source-bound audit support; all original R2 evidence stays unchanged."""
from pathlib import Path
import collections, datetime, hashlib, json, os, sys
os.environ.update(OMP_NUM_THREADS='2',OPENBLAS_NUM_THREADS='2',MKL_NUM_THREADS='2')
try:
    from OCP.OSD import OSD_ThreadPool
    OSD_ThreadPool.DefaultPool_s(2).SetNbDefaultThreadsToLaunch(2)
except ImportError:
    pass

R = Path(__file__).resolve().parents[1]
A = R
REBUILD = next(p for p in R.parents if p.name == 'no5-rebuild')
COMMON = REBUILD / 'tools/native-tools'
sys.path.insert(0, str(COMMON))
CAD = Path('/Users/chris/Library/Application Support/IoT-Fusion-Enclosures/cad-comparison-20261024-b123-current/bin/python')
QA = Path('/Users/chris/Library/Application Support/IoT-Fusion-Enclosures/pdf-render-current/bin/python')
KICAD = '/Volumes/production/Applications/KiCad/KiCad.app/Contents/MacOS/kicad-cli'
OUT = R/'reports/functional'
OUT.mkdir(parents=True,exist_ok=True)

def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()

def read(p):
    return json.loads(Path(p).read_text())

def save(p, value):
    p = Path(p); p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')

def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def finding(id, subject, requirement, status, evidence, consequence, scope='digital'):
    assert status in ('PASS', 'FAIL', 'OPEN')
    return dict(id=id, subject=subject, requirement=requirement, status=status,
                evidence=evidence, consequence=consequence, scope=scope)

def report(name, findings, **details):
    status = 'FAIL' if any(f['status'] == 'FAIL' for f in findings) else 'OPEN' if any(f['status'] == 'OPEN' for f in findings) else 'PASS'
    value = dict(schema='iot.module-function-audit/1', stage=name, run_status='PASS',
                 design_status=status, findings=findings, **details)
    save(OUT / (name + '.json'), value)
    print(name, 'executed;', dict(collections.Counter(f['status'] for f in findings)), flush=True)
    return value

class Graph:
    """Small existing-style adjacency/union-find equivalent; no extra dependency."""
    def __init__(self): self.adj = collections.defaultdict(set)
    def add(self, a, b): self.adj[a].add(b); self.adj[b].add(a)
    def group(self, start):
        seen = {start}; todo = [start]
        while todo:
            for nxt in self.adj[todo.pop()] - seen: seen.add(nxt); todo.append(nxt)
        return seen
    def path(self, a, b):
        todo = collections.deque([(a, [a])]); seen = {a}
        while todo:
            n, path = todo.popleft()
            if n == b: return path
            for z in self.adj[n] - seen: seen.add(z); todo.append((z, path + [z]))
        return None

