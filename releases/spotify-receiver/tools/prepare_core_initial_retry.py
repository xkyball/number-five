#!/usr/bin/env python3
"""Generate/check public core patch inputs; never mutate the Cargo registry."""
from __future__ import annotations

import argparse
import difflib
import hashlib
import json
from pathlib import Path


PROJECT = Path(__file__).resolve().parents[1]
BASE = {
    "src/dealer/mod.rs": "166e01ee2740e5998504100bee87ac02cceedfcd6317c8970531d43ce7d710ce",
    "src/dealer/manager.rs": "b4d5abaace7e379799dd271f83ed5b5b60e46c29ef95e666ddfffb989be8b82f",
    "src/http_client.rs": "cd4bc29ce25b0b820aa04ee6f4097656fbbdc69c16c02df1d6a5496616fd0852",
}


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def inputs(core: Path) -> tuple[dict, dict, str, dict]:
    before = {name: (core / name).read_bytes() for name in BASE}
    for name, expected in BASE.items():
        if sha(before[name]) != expected:
            raise ValueError(f"unexpected pinned core input: {name}")
    # The diagnostic prerequisite is independently hash-pinned and applied first.
    before["src/dealer/manager.rs"] = before["src/dealer/manager.rs"].replace(
        b"LaunchFailure(Error),", b"LaunchFailure(#[source] Error),", 1
    )
    mod = before["src/dealer/mod.rs"].decode().replace(
        "pub mod manager;\n",
        '''pub mod manager;
mod no5_setup_retry {
    use crate::{Error, error::ErrorKind, http_client::HttpClientError};
    use super::WsError;
    #[cfg(test)] use super::tungstenite;
    include!("no5_setup_retry.rs");
}
''', 1,
    ).replace(
        "pub async fn launch<Fut, F>(self, get_url: F, proxy: Option<Url>) -> WsResult<Dealer>",
        '''pub async fn launch<Fut, F>(
        self, get_url: F, proxy: Option<Url>,
        cancelled: impl Future<Output = ()>,
    ) -> WsResult<Dealer>''', 1,
    ).replace(
        '''            // Try to connect.
            let url = get_url().await?;
            let tasks = connect(&url, proxy.as_ref(), &shared).await?;
''',
        '''            // This block is evaluated before tokio::spawn: launch still waits
            // for initial establishment and returns its final typed error.
            let tasks = no5_setup_retry::retry_setup(
                no5_setup_retry::RetryStage::DealerConnect,
                || async {
                    let url = get_url().await?;
                    tokio::time::timeout(Duration::from_secs(15), connect(&url, proxy.as_ref(), &shared))
                        .await.map_err(|_| Error::deadline_exceeded("dealer establishment timeout"))?
                },
                cancelled,
            ).await?;
''', 1,
    )
    manager = before["src/dealer/manager.rs"].decode().replace(
        "        let get_url = move || Self::get_url(session.clone());",
        '''        let invalidated = session.clone();
        let get_url = move || Self::get_url(session.clone());
        let cancelled = async move {
            while !invalidated.is_invalid() {
                tokio::time::sleep(std::time::Duration::from_millis(100)).await;
            }
        };''', 1,
    ).replace(".launch(get_url, None)", ".launch(get_url, None, cancelled)", 1)
    shared_path = "vendor/librespot-connect/src/no5_setup_retry.rs"
    shared = (PROJECT / shared_path).read_bytes()
    http = before["src/http_client.rs"].decode().replace(
        "        let https_connector = HttpsConnector::new();",
        '''        let https_connector = {
            // Bound TCP establishment without cancelling Retry-After or a
            // Login5 challenge, and without replaying an HTTP operation here.
            let mut http = HttpConnector::new();
            http.enforce_http(false);
            http.set_connect_timeout(Some(Duration::from_secs(10)));
            HttpsConnector::new_with_connector(http)
        };''', 1,
    )
    after = {"src/dealer/mod.rs": mod.encode(), "src/dealer/manager.rs": manager.encode(),
             "src/dealer/no5_setup_retry.rs": shared, "src/http_client.rs": http.encode()}
    patch = "".join(
        "".join(difflib.unified_diff(
            before.get(name, b"").decode().splitlines(True), raw.decode().splitlines(True),
            fromfile="a/" + name if name in before else "/dev/null", tofile="b/" + name,
        )) for name, raw in after.items()
    )
    manifest = {
        "schema": 1, "core_version": "0.8.0",
        "apply_after": "librespot-core-0.8.0-dealer-error-source.patch",
        "patch_sha256": sha(patch.encode()),
        "files": {name: {"base_sha256": sha(before[name]) if name in before else None,
                         "patched_sha256": sha(raw)} for name, raw in after.items()},
        "shared_source": shared_path, "shared_source_sha256": sha(shared),
        "service_installation": False, "shared_registry_mutation_allowed": False,
    }
    return before, after, patch, manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-source", type=Path, required=True)
    parser.add_argument("--write", action="store_true", help="write only the two public patch artifacts")
    args = parser.parse_args()
    _, _, patch, manifest = inputs(args.core_source)
    artifacts = {
        "vendor/librespot-core-0.8.0-initial-retry.patch": patch,
        "vendor/librespot-core-0.8.0-initial-retry.json": json.dumps(manifest, indent=2) + "\n",
    }
    for name, contents in artifacts.items():
        path = PROJECT / name
        if args.write:
            path.write_text(contents)
        elif path.read_text() != contents:
            raise SystemExit(f"stale public patch artifact: {name}")
    print(json.dumps({"status": "WRITTEN" if args.write else "MATCH", **manifest}, indent=2))


if __name__ == "__main__":
    main()
