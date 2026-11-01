#!/usr/bin/env python3
"""Run the included invented-data core tests without external native providers."""
import os, pathlib, subprocess, sys
root=pathlib.Path(__file__).resolve().parent
env=dict(os.environ)
for name in ('NO5_S52_ENGINE','NO5_S52_ASSETS'):env.pop(name,None)
env['PYTHONPATH']=str(root/'src')
env['PYTHONDONTWRITEBYTECODE']='1'
raise SystemExit(subprocess.call([sys.executable,'-B','-m','pytest','-q','-p','no:cacheprovider','tests'],cwd=root,env=env))
