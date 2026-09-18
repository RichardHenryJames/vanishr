#requires -Version 7.4
param([ValidateSet('Plan', 'Deploy', 'Status')][string]$Action = 'Plan')
$ErrorActionPreference = 'Stop'
$subscription = '44027c71-593a-4d51-977b-ab0604cb76eb'
$group = 'vanishr-dev-rg'
$root = Split-Path $PSScriptRoot -Parent
$privateDirectory = Join-Path $root '.secrets/azure'
$statePath = Join-Path $privateDirectory 'deployment.json'
$privateKey = Join-Path $privateDirectory 'id_rsa'
$deploymentName = 'vanishr-isolated-v1'

function Invoke-Azure([string[]]$Arguments) {
    $result = & az @Arguments --subscription $subscription --only-show-errors --output json
    if ($LASTEXITCODE -ne 0) { throw 'Azure command failed. Deployment was not continued.' }
    if ($result) { return ($result -join [Environment]::NewLine) | ConvertFrom-Json }
}

$policy = Invoke-Azure @('rest', '--method', 'get', '--url', "https://management.azure.com/subscriptions/$subscription`?api-version=2022-12-01")
if ($policy.state -ne 'Enabled' -or $policy.subscriptionPolicies.spendingLimit -ne 'On') {
    throw 'The approved subscription must remain enabled with its spending limit on.'
}
$groupId = "/subscriptions/$subscription/resourceGroups/$group"
$exists = Invoke-Azure @('group', 'exists', '--name', $group)
if ($exists) {
    if (-not (Test-Path $statePath)) { throw 'An existing resource group has no local ownership record. It will not be modified.' }
    $existingGroup = Invoke-Azure @('group', 'show', '--name', $group)
    if ($existingGroup.tags.managedBy -ne 'vanishr-isolated-deployment') { throw 'Resource group ownership does not match. No changes are allowed.' }
}

if ($Action -eq 'Status') {
    if (-not $exists) { throw 'The isolated resource group has not been created.' }
    Invoke-Azure @('deployment', 'group', 'show', '--resource-group', $group, '--name', $deploymentName, '--query', 'properties.outputs.endpoint.value')
    return
}

if (-not (Test-Path $statePath)) {
    if (-not $IsWindows) { throw 'Initialize the protected deployment directory manually on this platform.' }
    New-Item -ItemType Directory -Path $privateDirectory -Force | Out-Null
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $acl = Get-Acl $privateDirectory
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
    Set-Acl $privateDirectory $acl
    if (Test-Path $privateKey) { throw 'An unrecorded SSH key exists; it will not be overwritten.' }
    & ssh-keygen -q -t rsa -b 3072 -f $privateKey -N '' -C 'vanishr-isolated-deployment'
    if ($LASTEXITCODE -ne 0) { throw 'Could not create a protected local SSH key.' }
    $addressText = (Invoke-RestMethod 'https://api.ipify.org').Trim()
    $address = [System.Net.IPAddress]::Parse($addressText)
    if ($address.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) { throw 'An operator IPv4 address is required.' }
    $account = Invoke-Azure @('account', 'show')
    if ($account.user.name -notmatch '^[^\s@]+@[^\s@]+\.[^\s@]+$') { throw 'A user email is required for budget notifications.' }
    $state = [ordered]@{
        subscription = $subscription
        resourceGroup = $group
        dnsLabel = 'vanishr-dev-' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
        sourceIpv4 = $address.ToString()
        notificationEmail = $account.user.name
    }
    [System.IO.File]::WriteAllText($statePath, ($state | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
}
$state = Get-Content $statePath -Raw | ConvertFrom-Json
if ($state.subscription -ne $subscription -or $state.resourceGroup -ne $group) { throw 'Local ownership does not match the approved deployment scope.' }
if (-not $exists) {
    $null = Invoke-Azure @('group', 'create', '--name', $group, '--location', 'centralindia', '--tags',
        'application=vanishr', 'environment=devtest', 'managedBy=vanishr-isolated-deployment', 'planningBudgetINR=1200')
}

$variables = @('VANISHR_SSH_PUBLIC_KEY', 'VANISHR_OPERATOR_IPV4', 'VANISHR_BUDGET_EMAIL', 'VANISHR_DNS_LABEL')
$previous = @{}
foreach ($variable in $variables) { $previous[$variable] = [Environment]::GetEnvironmentVariable($variable) }
try {
    $env:VANISHR_SSH_PUBLIC_KEY = (Get-Content "$privateKey.pub" -Raw).Trim()
    $env:VANISHR_OPERATOR_IPV4 = $state.sourceIpv4
    $env:VANISHR_BUDGET_EMAIL = $state.notificationEmail
    $env:VANISHR_DNS_LABEL = $state.dnsLabel
    $parameters = Join-Path $root 'infra/azure/vm.bicepparam'
    $plan = Invoke-Azure @('deployment', 'group', 'what-if', '--resource-group', $group, '--name', $deploymentName,
        '--parameters', $parameters, '--mode', 'Incremental', '--no-pretty-print')
    if ($plan.status -ne 'Succeeded') { throw 'Azure what-if did not succeed.' }
    foreach ($change in $plan.changes) {
        if (-not $change.resourceId.StartsWith("$groupId/", [StringComparison]::OrdinalIgnoreCase)) {
            throw 'What-if contains a change outside the isolated resource group. Deployment refused.'
        }
        if ($change.changeType -eq 'Delete') { throw 'What-if contains a deletion. Deployment refused.' }
    }
    [System.IO.File]::WriteAllText((Join-Path $privateDirectory 'what-if.json'), ($plan | ConvertTo-Json -Depth 100), [System.Text.UTF8Encoding]::new($false))
    $plan.changes | Select-Object changeType, resourceId | Format-Table -AutoSize
    if ($Action -eq 'Deploy') {
        $deployment = Invoke-Azure @('deployment', 'group', 'create', '--resource-group', $group, '--name', $deploymentName,
            '--parameters', $parameters, '--mode', 'Incremental')
        if ($deployment.properties.provisioningState -ne 'Succeeded') { throw 'Deployment did not succeed.' }
        $endpoint = $deployment.properties.outputs.endpoint.value
        [System.IO.File]::WriteAllText((Join-Path $privateDirectory 'endpoint.json'), ($endpoint | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
        $endpoint | Format-List
    } else {
        Write-Output 'Plan only: no VM or metered resources were created. All proposed changes are inside vanishr-dev-rg.'
    }
} finally {
    foreach ($variable in $variables) { [Environment]::SetEnvironmentVariable($variable, $previous[$variable]) }
}