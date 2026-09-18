#!/bin/sh
set -eu
umask 077
expected_hash=${expectedHash:?Expected artifact checksum is required}
hostname=${hostname:?Public hostname is required}
notification_email=${notificationEmail:?Notification email is required}
artifact_url=${artifactUrl:?Protected artifact URL is required}
test "$(id -u)" = 0
printf '%s' "$expected_hash" | grep -Eq '^[0-9a-f]{64}$'
printf '%s' "$hostname" | grep -Eq '^[a-z0-9.-]+\.cloudapp\.azure\.com$'
printf '%s' "$notification_email" | grep -Eq '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$'
if ! command -v curl >/dev/null; then
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends curl
fi
mkdir -p /opt/vanishr
cd /opt/vanishr
download_config=$(mktemp /run/vanishr-download.XXXXXX)
google_directory=''
trap 'rm -f "$download_config"; if [ -n "$google_directory" ]; then rm -rf "$google_directory"; fi' EXIT
printf 'url = "%s"\n' "$artifact_url" > "$download_config"
unset artifact_url artifactUrl
curl --config "$download_config" --fail --silent --show-error --proto '=https' --tlsv1.2 --max-time 300 --output upload.tgz
rm -f "$download_config"
printf '%s  upload.tgz\n' "$expected_hash" | sha256sum --check --status
tar -xzf upload.tgz
if [ -n "${googleConfiguration:-}" ]; then
    google_directory=$(mktemp -d /run/vanishr-google.XXXXXX)
    printf '%s' "$googleConfiguration" | base64 -d > "$google_directory/input.json"
    unset googleConfiguration
    jq -e '
        .projectId == "vanishr-b7616" and
        (.webClientId | test("\\A167823115834-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com\\z")) and
        (.androidClientIds | test("\\A167823115834-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com\\z")) and
        .credential.type == "service_account" and .credential.project_id == .projectId and
        .credential.client_email == "vanishr-push-sender@vanishr-b7616.iam.gserviceaccount.com" and
        .credential.client_id == "104536232033671436023" and
        .credential.token_uri == "https://oauth2.googleapis.com/token" and
        (.credential.private_key_id | test("\\A[0-9a-f]{40}\\z")) and
        (.credential.private_key | startswith("-----BEGIN PRIVATE KEY-----\n"))
    ' "$google_directory/input.json" >/dev/null 2>&1
    jq -c '.credential' "$google_directory/input.json" > "$google_directory/fcm-sender.json"
    jq -r '"GOOGLE_WEB_CLIENT_ID=\(.webClientId)\nGOOGLE_ANDROID_CLIENT_IDS=\(.androidClientIds)\nFCM_PROJECT_ID=\(.projectId)\nFCM_CREDENTIAL_FILE=/opt/vanishr/.secrets/firebase/fcm-sender.json"' \
        "$google_directory/input.json" > "$google_directory/google.env"
    install -d -m 700 .secrets .secrets/firebase
    install -o 10001 -g 10001 -m 400 "$google_directory/fcm-sender.json" .secrets/firebase/fcm-sender.json
    install -o 0 -g 0 -m 600 "$google_directory/google.env" .secrets/google.env
    rm -rf "$google_directory"
    google_directory=''
fi
jq -n --arg hostname "$hostname" --arg notificationEmail "$notification_email" \
    '{hostname:$hostname,notificationEmail:$notificationEmail}' > cloud-config.json
sh infra/azure/start-host.sh
rm -f upload.tgz