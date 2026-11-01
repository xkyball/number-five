"""Core tests with only bundled synthetic inputs; no hardware or external checkout."""
import os,sys,unittest
from pathlib import Path
r=Path(__file__).resolve().parent
os.environ.pop('NO5_HELM_CLIENT_ROOT',None)
sys.path[:0]=[str(r/'src'),str(r/'tests')]
# Peer-contract test path is fixed in original source and belongs to the full monorepo.
# Its original source is shipped; skip that1test in this standalone core runner.
suite=unittest.TestSuite()
pytest_modules=[]
for path in sorted((r/'tests').glob('test_*.py')):
 if 'import pytest' in path.read_text():pytest_modules.append(path.name);continue
 suite.addTests(unittest.TestLoader().discover(str(r/'tests'),pattern=path.name))
print('Pytest-only modules not executed by unittest:',pytest_modules)
def cases(x):
 for t in x:
  if isinstance(t,unittest.TestSuite):yield from cases(t)
  else:yield t
keep=[];skipped=[]
for t in cases(suite):
 if '.test_actual20_three_stage_vectors_'in t.id():skipped.append(t.id())
 else:keep.append(t)
print('External peer-path cases omitted:',len(skipped))
result=unittest.TextTestRunner(verbosity=1).run(unittest.TestSuite(keep))
sys.exit(not result.wasSuccessful())
