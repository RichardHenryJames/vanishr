#!/bin/sh
set -eu
systemctl is-active docker
cat /proc/swaps
free -m
docker compose version
docker ps -a --format '{{.Names}} {{.Status}}'
if docker inspect vanishr-https-1 >/dev/null 2>&1; then
    docker inspect --format '{{json .State.Health}}' vanishr-https-1
    docker inspect --format 'oom={{.State.OOMKilled}} restarts={{.RestartCount}} exit={{.State.ExitCode}}' vanishr-https-1
    docker inspect --format '{{.State.Error}}' vanishr-https-1
    docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' vanishr-relay-1 vanishr-redis-1 vanishr-postgres-1 vanishr-https-1
    cd /opt/vanishr
    set -- --env-file .secrets/local.env
    if [ -e .secrets/google.env ] || [ -e .secrets/firebase/fcm-sender.json ]; then
        test "$(stat -c '%u:%g:%a' .secrets/google.env)" = '0:0:600'
        test "$(stat -c '%u:%g:%a' .secrets/firebase/fcm-sender.json)" = '10001:10001:400'
        jq -e '.type == "service_account" and .project_id == "vanishr-b7616" and .client_email == "vanishr-push-sender@vanishr-b7616.iam.gserviceaccount.com"' \
            .secrets/firebase/fcm-sender.json >/dev/null
        set -- "$@" --env-file .secrets/google.env
    fi
    set -- "$@" -f infra/compose.yml -f infra/compose.low-memory.yml -f infra/azure/compose.public.yml
    if [ -f .secrets/google.env ]; then
        set -- "$@" -f infra/compose.google.yml
        docker compose "$@" exec -T relay sh -c \
            'test "$FCM_ENABLED" = true && test "$FCM_PROJECT_ID" = vanishr-b7616 && test "$GOOGLE_APPLICATION_CREDENTIALS" = /credentials/fcm.json && test -r /credentials/fcm.json && test ! -w /credentials/fcm.json'
        printf '%s\n' 'Provider runtime configuration and private read-only credential checks passed.'
    fi
    docker compose "$@" run --rm --no-deps https caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
fi