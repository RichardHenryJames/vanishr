#!/bin/sh
set -eu
cp /tls/postgres.key /tmp/postgres.key
chmod 600 /tmp/postgres.key
exec docker-entrypoint.sh postgres \
    -c ssl=on \
    -c ssl_cert_file=/tls/postgres.crt \
    -c ssl_key_file=/tmp/postgres.key \
    -c hba_file=/config/pg_hba.conf \
    -c shared_buffers="${POSTGRES_SHARED_BUFFERS:-128MB}" \
    -c work_mem="${POSTGRES_WORK_MEM:-4MB}" \
    -c maintenance_work_mem="${POSTGRES_MAINTENANCE_WORK_MEM:-64MB}" \
    -c max_connections="${POSTGRES_MAX_CONNECTIONS:-100}" \
    -c log_statement=none \
    -c log_min_error_statement=panic \
    -c log_min_messages=panic \
    -c log_connections=off \
    -c log_disconnections=off