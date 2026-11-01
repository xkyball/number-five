#!/usr/bin/env python3
"""Materialize ONE pinned build input, never a second Git checkout.

Own Rust sources and the small integration patch are versioned here. The
upstream archive is content checked and only unpacked into ignored .build/.
"""
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    pin = json.loads((ROOT / "upstream.json").read_text())
    build = ROOT / ".build"
    build.mkdir(exist_ok=True)
    archive = build / "upstream.tar.gz"
    if not archive.exists():
        with urllib.request.urlopen(pin["url"], timeout=60) as response:
            archive.write_bytes(response.read(4_000_001))
    if hashlib.sha256(archive.read_bytes()).hexdigest() != pin["sha256"]:
        raise SystemExit("upstream archive identity mismatch")
    source = build / ("camilladsp-" + pin["commit"])
    if not source.exists():
        with tarfile.open(archive) as handle:
            handle.extractall(build, filter="data")
    for name in ("no5_sound.rs",):
        shutil.copyfile(ROOT / "src" / name, source / "src" / name)
    shutil.copyfile(ROOT / "eq-core/src/lib.rs", source / "src/no5_eq_core.rs")
    shutil.copyfile(ROOT / "eq-core/src/kernel.sha256", source / "src/kernel.sha256")
    replacements = {
        "src/lib.rs": [("pub mod pipeline;", "pub mod no5_sound;\npub mod pipeline;")],
        "Cargo.toml": [
            ("[dependencies]", '[dependencies]\nsha2 = "=0.10.9"'),
            ('serde_json = "1.0"', 'serde_json = { version = "=1.0.154", features = ["float_roundtrip"] }'),
        ],
        "src/bin.rs": [
            ('use git_version::git_version;', ''),
            ('const GIT_HASH: &str = git_version!(fallback = "unknown");',
             'const GIT_HASH: &str = "05e9cfcdf43c0dfe078ed3feb8af4c8bd701fd74+no5-sound-v2";'),
        ],
        "src/processing.rs": [
            ('                Ok(AudioMessage::Pause) => {',
             '                Ok(AudioMessage::Pause) => {\n                    crate::no5_sound::mark_gap();'),
        ],
        "src/pipeline.rs": [
            ("    overloaded_chunks: usize,", "    overloaded_chunks: usize,\n    no5: Option<crate::no5_sound::Stages>,"),
            ('        debug!("Build new pipeline");', '        let no5 = crate::no5_sound::Stages::from_config(&conf);\n        debug!("Build new pipeline");'),
            ("            overloaded_chunks: 0,", "            overloaded_chunks: 0,\n            no5,"),
            ('        debug!("Updating parameters");', '        if let Some(no5) = self.no5.as_mut() { no5.invalidate(); }\n        debug!("Updating parameters");'),
            ("        self.volume.process_chunk(&mut chunk);", "        if let Some(no5) = self.no5.as_mut() { no5.pre(&mut chunk); }\n        else { self.volume.process_chunk(&mut chunk); }\n        let mut first_mixer = true;"),
            ("                    chunk = mix.process_chunk(chunk);", "                    chunk = mix.process_chunk(chunk);\n                    if first_mixer {\n                        if let Some(no5) = self.no5.as_mut() { no5.room(&mut chunk); }\n                        first_mixer = false;\n                    }"),
            ("        let secs_elapsed = start.elapsed().as_secs_f32();", "        if let Some(no5) = self.no5.as_mut() {\n            self.volume.process_chunk(&mut chunk);\n            no5.post(&mut chunk);\n        }\n        let secs_elapsed = start.elapsed().as_secs_f32();"),
        ],
        "src/socketserver.rs": [
            ("enum WsCommand {", "enum WsCommand {\n    GetNo5Sound,\n    SetNo5Sound(serde_json::Value),\n    SetNo5SoundLease(serde_json::Value),\n    SetNo5Spectrum(serde_json::Value),\n    GetNo5Spectrum,"),
            ("enum WsReply {", "enum WsReply {\n    GetNo5Sound { result: WsResult, value: serde_json::Value },\n    SetNo5Sound { result: WsResult, value: serde_json::Value },\n    SetNo5SoundLease { result: WsResult, value: serde_json::Value },\n    SetNo5Spectrum { result: WsResult, value: serde_json::Value },\n    GetNo5Spectrum { result: WsResult, value: serde_json::Value },"),
            ("    match command {\n        WsCommand::Reload", "    match command {\n        WsCommand::GetNo5Sound => Some(WsReply::GetNo5Sound { result: WsResult::Ok, value: crate::no5_sound::get_sound() }),\n        WsCommand::GetNo5Spectrum => Some(WsReply::GetNo5Spectrum { result: WsResult::Ok, value: crate::no5_sound::get_spectrum() }),\n        WsCommand::SetNo5Spectrum(value) => {\n            let (result, value) = match crate::no5_sound::set_spectrum(value) {\n                Ok(value) => (WsResult::Ok, value),\n                Err(error) => (WsResult::InvalidValueError(error), serde_json::Value::Null),\n            }; Some(WsReply::SetNo5Spectrum { result, value })\n        },\n        WsCommand::SetNo5SoundLease(value) => {\n            let (result, value) = match crate::no5_sound::set_sound_lease(value) {\n                Ok(value) => (WsResult::Ok, value),\n                Err(error) => (WsResult::InvalidValueError(error), serde_json::Value::Null),\n            }; Some(WsReply::SetNo5SoundLease { result, value })\n        },\n        WsCommand::SetNo5Sound(value) => {\n            let (result, value) = match crate::no5_sound::set_sound(value) {\n                Ok(value) => (WsResult::Ok, value),\n                Err(error) => (WsResult::InvalidValueError(error), serde_json::Value::Null),\n            }; Some(WsReply::SetNo5Sound { result, value })\n        },\n        WsCommand::Reload"),
        ],
    }
    # Restore pristine tracked integration inputs on repeat runs; own files stay.
    with tarfile.open(archive) as handle:
        for path, edits in replacements.items():
            member = handle.getmember(source.name + "/" + path)
            text = handle.extractfile(member).read().decode()
            for before, after in edits:
                if text.count(before) != 1:
                    raise SystemExit(f"patch anchor mismatch: {path}: {before[:70]!r}")
                text = text.replace(before, after)
            (source / path).write_text(text)
    lock = ROOT / "Cargo.lock"
    if lock.exists():
        shutil.copyfile(lock, source / "Cargo.lock")
    print(source)


if __name__ == "__main__":
    main()
