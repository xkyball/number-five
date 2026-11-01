#!/usr/bin/env python3
from pathlib import Path
import json,hashlib,sys
root=Path(__file__).resolve().parents[1]
m=json.loads((root/'docs/SOURCE_PROVENANCE.json').read_text())
entries=m['OpenCPN']['files']+json.loads((root/'chart-engine/third_party/PROVENANCE.json').read_text())
failed=[]
for entry in entries:
    p=root/entry['local_path']
    if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest()!=entry['sha256']:failed.append(entry['local_path'])
noaa_root=root/'chart-engine/src/main/assets/charts/noaa'
noaa=json.loads((noaa_root/'PROVENANCE.json').read_text())
chart_files=[entry for chart in noaa['charts'] for entry in chart['files']]+noaa['ancillary_files']
for entry in chart_files:
    p=noaa_root/entry['name']
    if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest()!=entry['sha256']:failed.append(str(p.relative_to(root)))
print(json.dumps({'pinned_commit':m['OpenCPN']['commit'],'OpenCPN_files_verified':len(m['OpenCPN']['files']),'third_party_files_verified':len(entries)-len(m['OpenCPN']['files']),'NOAA_cells_verified':len(noaa['charts']),'NOAA_files_verified':len(chart_files),'failures':failed}))
sys.exit(bool(failed))
