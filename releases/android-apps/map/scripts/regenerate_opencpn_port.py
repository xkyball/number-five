#!/usr/bin/env python3
"""Regenerate the non-UI port from pinned originals and explicit line edits.
Use --check to verify without writing. Each original and each output is hashed.
"""
from pathlib import Path
import argparse,hashlib,json
root=Path(__file__).resolve().parents[1]
args=argparse.ArgumentParser();args.add_argument('--check',action='store_true');options=args.parse_args()
manifest=json.loads((root/'chart-engine/no5/port/generated/PORT_MANIFEST.json').read_text())
for entry in manifest['files']:
    chunks=[]
    for source in entry['sources']:
        path=root/'chart-engine/opencpn'/source['path']
        assert hashlib.sha256(path.read_bytes()).hexdigest()==source['sha256'],path
        chunks.append(path.read_text())
    lines='\n'.join(chunks).splitlines(keepends=True)
    for change in reversed(entry['edits']):
        lines[change['start_line']:change['end_line_exclusive']]=[change['replacement']]
    output=''.join(lines).encode()
    assert hashlib.sha256(output).hexdigest()==entry['sha256'],entry['output']
    path=root/entry['output']
    if options.check:assert path.read_bytes()==output,path
    else:path.write_bytes(output)
print(json.dumps({'commit':manifest['commit'],'generated_files':len(manifest['files']),'mode':'check' if options.check else 'regenerate','passed':True}))
