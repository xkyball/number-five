# Operator-only gateway-key transfer to Lumina

The TLS proxy on lysmarine authenticates every public gateway request with the
root-owned Nginx map at `/etc/nginx/conf.d/no5-gateway-auth.conf`. This document
describes a **user-run** transfer to Lumina's existing V1 key-import workflow.
It does not grant access, change Pi configuration, read a live auth file, or
send any key to a service other than the explicitly selected local Android
device.

## Exact generated-map shape

The non-secret template is
[`deploy/nginx/no5-gateway-auth.conf.template`](../deploy/nginx/no5-gateway-auth.conf.template):

```nginx
map $http_x_no5_gateway_key $no5_gateway_authorized {
    default 0;
    "__NO5_GATEWAY_HEADER_KEY__" 1;
}
```

`tools/extract_gateway_header_key_from_nginx_map.py` accepts only that map
variable pair, one `default 0;` entry and exactly one quoted key-to-`1;`
entry. Comments and blank lines are allowed; any second key, different map,
unquoted value, unsupported escape or unexpected directive causes failure.
The script decodes only `\"` and `\\`, validates the resulting 16–512-byte
printable header value, and writes it to stdout with **no newline**. It never
prints key material in diagnostics.

## Prerequisites

* This is a deliberate operator action on the Mac that owns the authorised
  Lumina USB/ADB connection.
* The Pi account needs an already authorised **non-interactive** `sudo -n`
  read for exactly the root-owned auth map. No service, Nginx, token or file
  permission is changed by this process.
* The local helper
  `/Users/chris/projects/max/.helm-performance-lifecycle/helm/tools/stage_gateway_key_to_lumina.sh`
  must be present and executable. It receives only the raw key on stdin and
  writes the bounded V1 import document directly to Lumina Downloads.

## Preferred non-interactive sudo path

Run this **locally on the Mac**, from this repository. It intentionally puts
no key in shell arguments, environment variables, a temporary file, terminal
output or command history.

```sh
set -o pipefail
ssh -T -i /Users/chris/.ssh/no5_lysmarine_ed25519 \
  -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes \
  user@192.0.2.10 \
  'sudo -n /bin/cat -- /etc/nginx/conf.d/no5-gateway-auth.conf' \
| python3 tools/extract_gateway_header_key_from_nginx_map.py --stdin-auth-map \
| /Users/chris/projects/max/.helm-performance-lifecycle/helm/tools/stage_gateway_key_to_lumina.sh --operator-approved-stdin
```

The command fails closed if `sudo -n` is not already authorised, if SSH adds
unexpected output, if the map is not exactly the approved structure, if ADB is
unavailable, or if the downstream formatter rejects the raw header value.

## No interactive pipeline fallback

Do **not** replace `sudo -n` with `ssh -tt` plus an interactive `sudo`
prompt. A PTY can interleave prompt/banner text with the stdout pipe and make
password input routing unreliable. If the preferred command reports that
`sudo -n` is not authorised, stop without exporting anything. The operator
must first arrange a separate, non-secret non-interactive read authorisation
for this exact file, or perform the transfer by another reviewed method. Do
not redirect the map to a file or display it to diagnose the problem.

After success, import the staged document through Lumina SETTINGS and use its
verified readback/delete flow. This transfer does not itself test the gateway
connection or modify Pi-side access.
