"""Portable packaging adapter; original no5 build rules and source pins retained."""
import argparse,importlib.util,json
from pathlib import Path
root=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('no5_original_build',root/'app/tools/build_wheel.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.ROOT=root/'app';m.REPO=root/'source/no-pi';m.IOT=root/'source';m.SCOPE=root/'source/preserved-openecho'
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,default=root/'dist');args=p.parse_args()
result=m.build(args.output)
# Keep output concise; full build provenance is already inside the resulting wheel.
print(json.dumps({k:v for k,v in result.items()if k!='source'},indent=2))
