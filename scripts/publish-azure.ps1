#requires -Version 7.4
param([ValidateSet('Check', 'Publish')][string]$Action = 'Check', [string]$GoogleServicesFile, [string]$FcmCredentialFile)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$privateDirectory = Join-Path $root '.secrets/azure'
$endpoint = Get-Content (Join-Path $privateDirectory 'endpoint.json') -Raw | ConvertFrom-Json
$state = Get-Content (Join-Path $privateDirectory 'deployment.json') -Raw | ConvertFrom-Json
if ($state.subscription -ne '44027c71-593a-4d51-977b-ab0604cb76eb' -or $state.resourceGroup -ne 'vanishr-dev-rg' -or $endpoint.resourceGroup -ne $state.resourceGroup) {
    throw 'Deployment scope does not match the approved isolated group.'
}
$googleConfiguration = $null
if ([bool]$GoogleServicesFile -ne [bool]$FcmCredentialFile -or ($Action -ne 'Publish' -and $GoogleServicesFile)) {
    throw 'Google setup requires Publish with both client configuration and server credential paths.'
}
if ($GoogleServicesFile) {
    $googleSettings = & (Join-Path $PSScriptRoot 'read-google-config.ps1') -Path $GoogleServicesFile
    if ($googleSettings.FCM_PROJECT_ID -cne 'vanishr-b7616') { throw 'Google setup is restricted to the approved Vanishr Firebase project.' }
    if (-not $IsWindows) { throw 'Verify private credential permissions on this platform before publication.' }
    $currentSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    foreach ($path in @($privateDirectory, $FcmCredentialFile)) {
        $acl = Get-Acl -LiteralPath $path
        $rules = @($acl.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier]))
        if ($rules.Count -ne 1 -or $rules[0].IdentityReference.Value -ne $currentSid -or $rules[0].AccessControlType -ne 'Allow') {
            throw 'Google credentials and transfer files require current-user-only filesystem access.'
        }
    }
    try {
        if ((Get-Item -LiteralPath $FcmCredentialFile).Length -gt 16KB) { throw 'Credential is too large.' }
        $credential = Get-Content -LiteralPath $FcmCredentialFile -Raw | ConvertFrom-Json -ErrorAction Stop
        if ($credential.type -cne 'service_account' -or $credential.project_id -cne $googleSettings.FCM_PROJECT_ID -or
            $credential.client_email -cne 'vanishr-push-sender@vanishr-b7616.iam.gserviceaccount.com' -or
            $credential.client_id -cne '104536232033671436023' -or $credential.token_uri -cne 'https://oauth2.googleapis.com/token' -or
            $credential.private_key_id -cnotmatch '\A[0-9a-f]{40}\z') { throw 'Credential metadata does not match.' }
        $rsa = [System.Security.Cryptography.RSA]::Create()
        $rsa.ImportFromPem($credential.private_key)
        if ($rsa.KeySize -lt 2048) { throw 'Credential key is invalid.' }
        $provider = @{ projectId = $googleSettings.FCM_PROJECT_ID; webClientId = $googleSettings.GOOGLE_WEB_CLIENT_ID;
            androidClientIds = $googleSettings.GOOGLE_ANDROID_CLIENT_IDS; credential = $credential }
        $googleConfiguration = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes(($provider | ConvertTo-Json -Depth 8 -Compress)))
    } catch { throw 'The private Google credential failed validation; no credential contents were displayed.' }
    finally { if ($rsa) { $rsa.Dispose() }; $credential = $null; $provider = $null }
}
function Invoke-Azure([string[]]$Arguments) {
    $result = & az @Arguments --subscription $state.subscription --only-show-errors --output json 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Azure publication operation failed; no other resource groups were modified.' }
    if ($result) { return ($result -join [Environment]::NewLine) | ConvertFrom-Json }
}
$group = Invoke-Azure @('group', 'show', '--name', $state.resourceGroup)
if ($group.tags.managedBy -ne 'vanishr-isolated-deployment') { throw 'Resource group ownership changed. Publication refused.' }
if ($Action -eq 'Check') {
    $result = Invoke-Azure @('vm', 'run-command', 'invoke', '--resource-group', $state.resourceGroup, '--name', $endpoint.vmName,
        '--command-id', 'RunShellScript', '--scripts', 'systemctl is-active docker', 'cat /proc/swaps', 'free -m', 'docker compose version')
    $result.value.message | Write-Output
    return
}

$archive = Join-Path $root '.tools/azure-upload.tgz'
$storageName = 'vnrstage' + [Guid]::NewGuid().ToString('N').Substring(0, 14)
$requestPath = Join-Path $privateDirectory 'install-request.json'
$oldStorageKey = $env:AZURE_STORAGE_KEY
$storageCreated = $false
Push-Location $root
try {
    $allowedFiles = @('.dockerignore', 'infra/Dockerfile', 'infra/compose.yml', 'infra/compose.low-memory.yml', 'infra/pg_hba.conf',
        'infra/postgres-entrypoint.sh', 'infra/azure/compose.public.yml', 'infra/azure/Caddyfile', 'infra/azure/start-host.sh',
        'infra/compose.google.yml', 'relay/target/relay-0.1.0-SNAPSHOT.jar')
    & tar -czf $archive @allowedFiles
    if ($LASTEXITCODE -ne 0) { throw 'Could not package the explicitly allowed deployment files.' }
    $packaged = & tar -tzf $archive
    if ($LASTEXITCODE -ne 0 -or @($packaged).Count -ne $allowedFiles.Count) { throw 'Deployment archive inventory is invalid.' }
    foreach ($file in $packaged) { if ($file -notin $allowedFiles) { throw 'Unexpected file in deployment archive.' } }
    $null = Invoke-Azure @('storage', 'account', 'create', '--name', $storageName, '--resource-group', $state.resourceGroup,
        '--location', 'centralindia', '--sku', 'Standard_LRS', '--kind', 'StorageV2', '--https-only', 'true',
        '--min-tls-version', 'TLS1_2', '--allow-blob-public-access', 'false', '--tags', 'application=vanishr', 'purpose=temporary-artifact-transfer')
    $storageCreated = $true
    $keys = Invoke-Azure @('storage', 'account', 'keys', 'list', '--account-name', $storageName, '--resource-group', $state.resourceGroup)
    $env:AZURE_STORAGE_KEY = $keys[0].value
    $keys = $null
    $null = Invoke-Azure @('storage', 'container', 'create', '--account-name', $storageName, '--name', 'artifact', '--public-access', 'off')
    $null = Invoke-Azure @('storage', 'blob', 'upload', '--account-name', $storageName, '--container-name', 'artifact', '--name', 'relay.tgz',
        '--file', $archive, '--content-type', 'application/gzip', '--content-cache-control', 'no-store', '--overwrite', 'false')
    $expiry = [DateTime]::UtcNow.AddMinutes(20).ToString('yyyy-MM-ddTHH:mmZ')
    $artifactUrl = Invoke-Azure @('storage', 'blob', 'generate-sas', '--account-name', $storageName, '--container-name', 'artifact',
        '--name', 'relay.tgz', '--permissions', 'r', '--expiry', $expiry, '--https-only', '--full-uri')
    $protectedParameters = @(@{ name = 'artifactUrl'; value = $artifactUrl })
    if ($googleConfiguration) { $protectedParameters += @{ name = 'googleConfiguration'; value = $googleConfiguration } }
    $request = @{
        location = 'centralindia'
        properties = @{
            source = @{ script = Get-Content (Join-Path $root 'infra/azure/install-artifact.sh') -Raw }
            parameters = @(
                @{ name = 'expectedHash'; value = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant() },
                @{ name = 'hostname'; value = $endpoint.hostname },
                @{ name = 'notificationEmail'; value = $state.notificationEmail }
            )
            protectedParameters = $protectedParameters
            timeoutInSeconds = 1200
            asyncExecution = $false
            treatFailureAsDeploymentFailure = $true
        }
    }
    [System.IO.File]::WriteAllText($requestPath, ($request | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
    $commandId = "/subscriptions/$($state.subscription)/resourceGroups/$($state.resourceGroup)/providers/Microsoft.Compute/virtualMachines/$($endpoint.vmName)/runCommands/install-vanishr"
    $null = Invoke-Azure @('rest', '--method', 'put', '--url', "https://management.azure.com$commandId`?api-version=2024-07-01", '--body', "@$requestPath")
    $null = Invoke-Azure @('vm', 'run-command', 'wait', '--resource-group', $state.resourceGroup, '--vm-name', $endpoint.vmName,
        '--name', 'install-vanishr', '--created', '--interval', '10', '--timeout', '1200')
    $execution = Invoke-Azure @('vm', 'run-command', 'show', '--resource-group', $state.resourceGroup, '--vm-name', $endpoint.vmName,
        '--name', 'install-vanishr', '--expand', 'instanceView')
    if ($execution.instanceView.exitCode -ne 0 -or $execution.instanceView.executionState -ne 'Succeeded') {
        throw 'Artifact installation failed; inspect the isolated VM command status.'
    }
    Write-Output "Application installed on https://$($endpoint.hostname). Public TLS verification is the next gate."
} finally {
    $env:AZURE_STORAGE_KEY = $oldStorageKey
    $googleConfiguration = $null
    $protectedParameters = $null
    $request = $null
    if (Test-Path $requestPath) { Remove-Item -LiteralPath $requestPath }
    if ($storageCreated) {
        & az storage account delete --subscription $state.subscription --resource-group $state.resourceGroup --name $storageName --yes --only-show-errors --output none
        if ($LASTEXITCODE -ne 0) { Write-Warning "Remove the temporary artifact account $storageName in vanishr-dev-rg; cleanup failed." }
        else { Write-Output 'Temporary artifact storage removed.' }
    }
    Pop-Location
}