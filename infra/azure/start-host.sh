#!/bin/sh
set -eu
umask 077
cd /opt/vanishr
test "$(id -u)" = 0
test -f cloud-config.json
hostname=$(jq -er '.hostname' cloud-config.json)
email=$(jq -er '.notificationEmail' cloud-config.json)
printf '%s' "$hostname" | grep -Eq '^[a-z0-9.-]+\.cloudapp\.azure\.com$'
printf '%s' "$email" | grep -Eq '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$'
test "$(wc -l < /proc/swaps)" -eq 1
test "$(cat /proc/sys/fs/suid_dumpable)" -eq 0
mkdir -p .secrets
chmod 700 .secrets

if [ ! -f .secrets/local.env ]; then
    if [ -f .secrets/ca.key ] || [ -f .secrets/relay.p12 ]; then
        printf '%s\n' 'Incomplete private configuration exists; refusing to overwrite it.' >&2
        exit 1
    fi
    openssl rand -base64 32 > .secrets/tls-password
    openssl rand -base64 32 > .secrets/redis-password
    openssl rand -base64 32 > .secrets/postgres-password
    openssl req -x509 -newkey rsa:3072 -nodes -days 3650 -subj '/CN=Vanishr Internal CA' \
        -addext 'basicConstraints=critical,CA:TRUE' -addext 'keyUsage=critical,keyCertSign,cRLSign' \
        -keyout .secrets/ca.key -out .secrets/ca.crt >/dev/null 2>&1
    for service in relay redis postgres; do
        openssl req -new -newkey rsa:3072 -nodes -subj "/CN=$service" \
            -keyout ".secrets/$service.key" -out ".secrets/$service.csr" >/dev/null 2>&1
        printf 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:%s,DNS:localhost,IP:127.0.0.1\n' "$service" > ".secrets/$service.ext"
        openssl x509 -req -in ".secrets/$service.csr" -CA .secrets/ca.crt -CAkey .secrets/ca.key \
            -CAcreateserial -days 365 -sha256 -extfile ".secrets/$service.ext" -out ".secrets/$service.crt" >/dev/null 2>&1
    done
    openssl pkcs12 -export -name relay -inkey .secrets/relay.key -in .secrets/relay.crt \
        -certfile .secrets/ca.crt -out .secrets/relay.p12 -passout file:.secrets/tls-password >/dev/null 2>&1
    redis_password=$(cat .secrets/redis-password)
    database_password=$(cat .secrets/postgres-password)
    tls_password=$(cat .secrets/tls-password)
    printf 'bind 0.0.0.0\nport 0\ntls-port 6379\ntls-cert-file /tls/redis.crt\ntls-key-file /tls/redis.key\ntls-ca-cert-file /tls/ca.crt\ntls-auth-clients no\nrequirepass %s\nsave ""\nappendonly no\nmaxmemory 24mb\nmaxmemory-policy noeviction\nslowlog-log-slower-than -1\nslowlog-max-len 0\ndir /data\nloglevel warning\n' "$redis_password" > .secrets/redis.conf
    printf 'PORT=8443\nDATABASE_PASSWORD=%s\nREDIS_PASSWORD=%s\nTLS_KEYSTORE_PASSWORD=%s\nFCM_ENABLED=false\nVANISHR_HOSTNAME=%s\nVANISHR_CERT_EMAIL=%s\n' \
        "$database_password" "$redis_password" "$tls_password" "$hostname" "$email" > .secrets/local.env
    chmod 644 .secrets/ca.crt .secrets/redis.crt .secrets/redis.key .secrets/redis.conf .secrets/postgres.crt .secrets/postgres.key .secrets/relay.p12
    rm -f .secrets/ca.key .secrets/ca.srl .secrets/relay.key .secrets/relay.crt .secrets/*.csr .secrets/*.ext .secrets/*-password
    unset redis_password database_password tls_password
fi

mkdir -p .secrets/caddy-data
chown 10002:10002 .secrets/caddy-data
chmod 700 .secrets/caddy-data
set -- --env-file .secrets/local.env
if [ -e .secrets/google.env ] || [ -e .secrets/firebase/fcm-sender.json ]; then
    test -f .secrets/google.env
    test -f .secrets/firebase/fcm-sender.json
    test "$(stat -c '%u:%g:%a' .secrets/google.env)" = '0:0:600'
    test "$(stat -c '%u:%g:%a' .secrets/firebase/fcm-sender.json)" = '10001:10001:400'
    set -- "$@" --env-file .secrets/google.env
fi
set -- "$@" -f infra/compose.yml -f infra/compose.low-memory.yml -f infra/azure/compose.public.yml
if [ -f .secrets/google.env ]; then
    set -- "$@" -f infra/compose.google.yml
fi
docker compose "$@" config --quiet
docker compose "$@" up --build --wait --wait-timeout 240
docker compose "$@" ps --format '{{.Service}} {{.State}} {{.Health}}'
printf 'Public relay configured: https://%s\n' "$hostname"