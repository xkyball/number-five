#!/usr/bin/env python3
"""Check the tracked Nginx templates with isolated, synthetic TLS inputs.

Runs nginx -t only with private temporary Unix sockets. Reads no live inputs.
"""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def main():
    nginx = shutil.which("nginx")
    if nginx is None and Path("/usr/sbin/nginx").is_file():
        nginx = "/usr/sbin/nginx"
    if nginx is None:
        raise SystemExit("NOT RUN: nginx executable is unavailable")
    project = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix="no5-nginx-template-") as directory:
        root = Path(directory)
        certificate = root / "fixture.crt"
        key = root / "fixture.key"
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
            "-days", "1", "-subj", "/CN=localhost",
            "-keyout", str(key), "-out", str(certificate),
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        site = (project / "deploy/nginx/no5-gateway.conf").read_text()
        site = site.replace("include /etc/no5-gateway-proxy/nginx-browser-listeners.conf;",
                            "listen 127.0.0.1:443 ssl;")
        simulator = (project / "deploy/nginx/no5-simulator-locations.conf").read_text()
        simulator = simulator.replace("include /etc/no5-gateway-proxy/nginx-simulator-upstream-auth.conf;",
                                      'proxy_set_header Authorization "Bearer synthetic-fixture-only";')
        site = site.replace("include /etc/no5-gateway-proxy/nginx-simulator-locations.conf;", simulator)
        site = site.replace("/etc/no5-gateway-proxy/tls/server.crt", str(certificate))
        site = site.replace("/etc/no5-gateway-proxy/tls/server.key", str(key))
        # nginx -t checks bindability too. The boat address is unavailable on
        # CI; use private Unix sockets while preserving each listen option.
        listener = 0
        def isolated_listen(match):
            nonlocal listener
            listener += 1
            return match[1] + "listen unix:" + str(root / ("listener-" + str(listener) + ".sock")) + match[2] + ";"
        site = re.sub(r"(?m)^(\s*)listen\s+\S+(.*?);$", isolated_listen, site)
        maps = (project / "deploy/nginx/01-no5-camillagui-map.conf").read_text()
        maps += (project / "deploy/nginx/no5-simulator-maps.conf").read_text()
        maps += (project / "deploy/nginx/no5-lumina-token.conf.template").read_text()
        fixture = (
            "pid " + str(root / "nginx.pid") + ";\n"
            "error_log stderr;\nevents {}\nhttp {\naccess_log off;\n"
            "map $http_x_no5_gateway_key $no5_gateway_authorized { default 0; }\n"
            "map $http_upgrade $connection_upgrade { default upgrade; '' close; }\n"
            + maps + "\n" + site + "\n}\n"
        )
        config = root / "nginx.conf"
        config.write_text(fixture)
        subprocess.run([nginx, "-t", "-p", str(root) + "/", "-c", str(config)], check=True)
    print("PASS: tracked Nginx syntax with synthetic TLS; no runtime activation")


if __name__ == "__main__":
    main()
