#!/usr/bin/env python3
"""Offline, source-bound Rust/JNI build. No adjacent Pi checkout, downloads or VM starts."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,platform
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);a=p.parse_args()
repo=Path(__file__).resolve().parents[1];src=repo/'shared-eq';m=json.loads((src/'MANIFEST.json').read_text())
assert hashlib.sha256(Path(__file__).read_bytes()).hexdigest()==m['builderSha256']
for name,wanted in m['sourceSha256'].items():
 f=src/'core'/name;assert not f.is_symlink()and hashlib.sha256(f.read_bytes()).hexdigest()==wanted,'Shared source differs: '+name
assert hashlib.sha256((src/'core/src/lib.rs').read_bytes()).hexdigest()==(src/'core/src/kernel.sha256').read_text().strip()
assert hashlib.sha256((src/'jni.c').read_bytes()).hexdigest()==m['jniSha256']
assert subprocess.check_output(['rustc','--version'],text=True).strip()==m['rustc']
host={'Darwin':'darwin-x86_64','Linux':'linux-x86_64'}.get(platform.system());assert host,'Supported build hosts: Darwin or Linux'
ndk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))/'ndk'/m['ndk'];clang=ndk/'toolchains/llvm/prebuilt'/host/'bin/aarch64-linux-android29-clang'
a.output.mkdir(parents=True,exist_ok=True);target=a.output/'cargo';env=dict(os.environ,CARGO_BUILD_JOBS='2',CARGO_INCREMENTAL='0',RUSTFLAGS='--remap-path-prefix='+str(src/'core')+'=no5-eq-core',CARGO_TARGET_DIR=str(target),CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=str(clang))
subprocess.run(['cargo','build','--release','--offline','--locked','--target',m['target'],'--manifest-path',str(src/'core/Cargo.toml')],env=env,check=True)
out=a.output/'jni/arm64-v8a/libno5_eq.so';out.parent.mkdir(parents=True,exist_ok=True)
subprocess.run([str(clang),'-shared','-O2','-fPIC','-Wl,-z,max-page-size=16384',str(src/'jni.c'),str(target/m['target']/'release/libno5_eq_core.a'),'-ldl','-lm','-o',str(out)],check=True)
receipt={'source':m,'librarySha256':hashlib.sha256(out.read_bytes()).hexdigest(),'jniSha256':hashlib.sha256((src/'jni.c').read_bytes()).hexdigest(),'clang':subprocess.check_output([str(clang),'--version'],text=True).splitlines()[0]}
(a.output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print('SHARED_EQ_ANDROID_BUILD_PASS '+receipt['librarySha256'])
