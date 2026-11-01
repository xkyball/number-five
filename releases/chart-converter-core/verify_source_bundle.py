#!/usr/bin/env python3
"""Verify released file identities and Python syntax using only the standard library."""
import ast,hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parent
manifest=json.loads((root/'manifest.json').read_text())
for row in manifest['files']:
 p=root/row['path']
 assert p.is_file() and not p.is_symlink(),row['path']
 raw=p.read_bytes()
 assert len(raw)==row['bytes'] and hashlib.sha256(raw).hexdigest()==row['sha256'],row['path']
 if p.suffix=='.py':ast.parse(raw,filename=row['path'])
print(f"PASS: {len(manifest['files'])} file hashes; all included Python source parses.")
