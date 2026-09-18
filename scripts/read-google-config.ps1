#requires -Version 7.4
param([Parameter(Mandatory)][string]$Path)
$ErrorActionPreference = 'Stop'
try {
    if ((Get-Item -LiteralPath $Path).Length -gt 1MB) { throw 'Configuration is too large.' }
    $configuration = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -ErrorAction Stop
} catch { throw 'Could not read the Firebase client configuration; no file contents were displayed.' }
if ($configuration.type -eq 'service_account' -or $configuration.private_key) {
    throw 'A server credential must never be used as Android client configuration.'
}
$projectId = [string]$configuration.project_info.project_id
$projectNumber = [string]$configuration.project_info.project_number
if ($projectId -cnotmatch '\A[a-z][a-z0-9-]{4,28}[a-z0-9]\z' -or $projectNumber -cnotmatch '\A[0-9]+\z') {
    throw 'Firebase project identifiers are invalid.'
}
$clients = @($configuration.client | Where-Object { $_.client_info.android_client_info.package_name -ceq 'app.vanishr.android' })
if ($clients.Count -ne 1) { throw 'Exactly one release Android app must match the client configuration.' }
$client = $clients[0]
$webClients = @($client.oauth_client | Where-Object client_type -eq 3)
$androidClients = @($client.oauth_client | Where-Object {
    $_.client_type -eq 1 -and $_.android_info.package_name -ceq 'app.vanishr.android' -and
    $_.android_info.certificate_hash -ieq '1bfcf77f9ae8c14a53fcd79ceee0e9d2a8f1c0ca'
})
if ($webClients.Count -ne 1 -or $androidClients.Count -ne 1) {
    throw 'One Web OAuth client and one Android OAuth client matching the release certificate are required.'
}
$oauthPattern = '\A' + [regex]::Escape($projectNumber) + '-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com\z'
if ($webClients[0].client_id -cnotmatch $oauthPattern -or $androidClients[0].client_id -cnotmatch $oauthPattern) {
    throw 'OAuth client IDs do not match the Firebase project number.'
}
$appIdPattern = '\A1:' + [regex]::Escape($projectNumber) + ':android:[0-9a-f]+\z'
if ($client.client_info.mobilesdk_app_id -cnotmatch $appIdPattern -or @($client.api_key).Count -ne 1 -or
    $client.api_key[0].current_key -cnotmatch '\AAIza[A-Za-z0-9_-]{35}\z') {
    throw 'Firebase Android app settings are invalid.'
}
return @{
    GOOGLE_WEB_CLIENT_ID = [string]$webClients[0].client_id
    GOOGLE_ANDROID_CLIENT_IDS = [string]$androidClients[0].client_id
    FIREBASE_APP_ID = [string]$client.client_info.mobilesdk_app_id
    FIREBASE_API_KEY = [string]$client.api_key[0].current_key
    FIREBASE_PROJECT_ID = $projectId
    FIREBASE_SENDER_ID = $projectNumber
    FCM_PROJECT_ID = $projectId
}