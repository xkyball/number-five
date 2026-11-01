#!/bin/sh
# Renew the No5 Pi gateway server certificate without changing its local CA.
# Run this script as root after it has been installed root-owned (mode 0700).
set -eu

tls_dir=/etc/no5-gateway-proxy/tls
stage=pre-lysmarine-local-20261006
dns_name=lysmarine.local

if [ "$(id -u)" -ne 0 ]; then
    echo "must run as root" >&2
    exit 1
fi

verify_staged_certificate() {
    openssl verify -CAfile "$tls_dir/ca.crt" "$tls_dir/server.crt.next"
    openssl x509 -in "$tls_dir/server.crt.next" -noout -checkhost "$dns_name" \
        | grep -Fx "Hostname $dns_name does match certificate" >/dev/null

    key_digest=$(openssl pkey -in "$tls_dir/server.key.next" -pubout -outform DER \
        | openssl dgst -sha256)
    certificate_digest=$(openssl x509 -in "$tls_dir/server.crt.next" -pubkey -noout \
        | openssl pkey -pubin -outform DER | openssl dgst -sha256)
    if [ "$key_digest" != "$certificate_digest" ]; then
        echo "new server key does not match the new certificate" >&2
        exit 1
    fi
}

activate_staged_certificate() {
    verify_staged_certificate

    mv "$tls_dir/server.key.next" "$tls_dir/server.key"
    mv "$tls_dir/server.csr.next" "$tls_dir/server.csr"
    mv "$tls_dir/server.ext.next" "$tls_dir/server.ext"
    mv "$tls_dir/server.crt.next" "$tls_dir/server.crt"

    if ! nginx -t; then
        cp -a "$tls_dir/server.key.$stage" "$tls_dir/server.key"
        cp -a "$tls_dir/server.csr.$stage" "$tls_dir/server.csr"
        cp -a "$tls_dir/server.ext.$stage" "$tls_dir/server.ext"
        cp -a "$tls_dir/server.crt.$stage" "$tls_dir/server.crt"
        nginx -t
        exit 1
    fi

    systemctl reload nginx.service
    systemctl is-active --quiet nginx.service
    printf 'tls_renewal=ready\n'
}

if [ "$#" -eq 1 ] && [ "$1" = "--activate-staged" ]; then
    for file in server.key server.crt server.csr server.ext; do
        if [ ! -f "$tls_dir/$file.$stage" ] || [ ! -f "$tls_dir/$file.next" ]; then
            echo "complete staged renewal is not available: $file" >&2
            exit 1
        fi
    done
    activate_staged_certificate
    exit 0
fi

if [ "$#" -ne 0 ]; then
    echo "usage: $0 [--activate-staged]" >&2
    exit 1
fi

for file in ca.crt ca.key server.key server.crt server.csr server.ext; do
    if [ ! -f "$tls_dir/$file" ]; then
        echo "required TLS file is missing: $file" >&2
        exit 1
    fi
done

# Do not overwrite an existing recovery point or incomplete staged renewal.
for file in server.key server.crt server.csr server.ext; do
    if [ -e "$tls_dir/$file.$stage" ]; then
        echo "recovery file already exists: $file.$stage" >&2
        exit 1
    fi
done
for file in server.key server.crt server.csr server.ext; do
    if [ -e "$tls_dir/$file.next" ]; then
        echo "staged renewal file already exists: $file.next" >&2
        exit 1
    fi
done

umask 077
for file in server.key server.crt server.csr server.ext; do
    cp -a "$tls_dir/$file" "$tls_dir/$file.$stage"
done

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 \
    -out "$tls_dir/server.key.next"
openssl req -new -key "$tls_dir/server.key.next" \
    -subj "/CN=$dns_name" \
    -out "$tls_dir/server.csr.next"
printf '%s\n' \
    'basicConstraints=critical,CA:FALSE' \
    'keyUsage=critical,digitalSignature,keyEncipherment' \
    'extendedKeyUsage=serverAuth' \
    'subjectAltName=DNS:lysmarine.local,DNS:lysmarine,IP:192.0.2.10' \
    > "$tls_dir/server.ext.next"
openssl x509 -req -in "$tls_dir/server.csr.next" \
    -CA "$tls_dir/ca.crt" -CAkey "$tls_dir/ca.key" \
    -CAserial "$tls_dir/ca.srl" -days 825 -sha256 \
    -extfile "$tls_dir/server.ext.next" \
    -out "$tls_dir/server.crt.next"

chown root:root "$tls_dir/server.key.next" "$tls_dir/server.crt.next" \
    "$tls_dir/server.csr.next" "$tls_dir/server.ext.next"
chmod 0600 "$tls_dir/server.key.next"
chmod 0644 "$tls_dir/server.crt.next" "$tls_dir/server.csr.next" \
    "$tls_dir/server.ext.next"

activate_staged_certificate
