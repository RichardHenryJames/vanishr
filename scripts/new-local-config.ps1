#requires -Version 7.4
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$secrets = Join-Path $root '.secrets'
$environmentFile = Join-Path $secrets 'local.env'
if (Test-Path $environmentFile) { Write-Output 'Local configuration already exists; it was not changed.'; return }
New-Item -ItemType Directory -Path $secrets -Force | Out-Null
if ($IsWindows) {
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $acl = Get-Acl $secrets
    $acl.SetAccessRuleProtection($true, $false)
    $rule = [System.Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.SetAccessRule($rule)
    Set-Acl $secrets $acl
}
function New-Secret {
    [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
}
function Write-GeneratedText([string]$name, [string]$text) {
    [System.IO.File]::WriteAllText((Join-Path $secrets $name), $text, [System.Text.UTF8Encoding]::new($false))
}
$hash = [System.Security.Cryptography.HashAlgorithmName]::SHA256
$padding = [System.Security.Cryptography.RSASignaturePadding]::Pkcs1
$notBefore = [DateTimeOffset]::UtcNow.AddMinutes(-5)
$notAfter = [DateTimeOffset]::UtcNow.AddDays(7)
$caKey = [System.Security.Cryptography.RSA]::Create(3072)
$caRequest = [System.Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=Vanishr Local Development CA', $caKey, $hash, $padding)
$caRequest.CertificateExtensions.Add([System.Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($true, $false, 0, $true))
$caRequest.CertificateExtensions.Add([System.Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new([System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::KeyCertSign, $true))
$ca = $caRequest.CreateSelfSigned($notBefore, $notAfter.AddDays(1))
Write-GeneratedText 'ca.crt' ($ca.ExportCertificatePem())
$tlsPassword = New-Secret
foreach ($service in @('relay', 'redis', 'postgres')) {
    $key = [System.Security.Cryptography.RSA]::Create(3072)
    $request = [System.Security.Cryptography.X509Certificates.CertificateRequest]::new("CN=$service", $key, $hash, $padding)
    $request.CertificateExtensions.Add([System.Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($false, $false, 0, $true))
    $usage = [System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature -bor [System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::KeyEncipherment
    $request.CertificateExtensions.Add([System.Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new($usage, $true))
    $purposes = [System.Security.Cryptography.OidCollection]::new()
    $null = $purposes.Add([System.Security.Cryptography.Oid]::new('1.3.6.1.5.5.7.3.1'))
    $request.CertificateExtensions.Add([System.Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]::new($purposes, $false))
    $names = [System.Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
    $names.AddDnsName($service)
    $names.AddDnsName('localhost')
    $names.AddIpAddress([System.Net.IPAddress]::Parse('127.0.0.1'))
    if ($service -eq 'relay') { $names.AddIpAddress([System.Net.IPAddress]::Parse('10.0.2.2')) }
    $request.CertificateExtensions.Add($names.Build())
    $serial = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(16)
    $issued = $request.Create($ca, $notBefore, $notAfter, $serial)
    $certificate = [System.Security.Cryptography.X509Certificates.RSACertificateExtensions]::CopyWithPrivateKey($issued, $key)
    if ($service -eq 'relay') {
        [System.IO.File]::WriteAllBytes((Join-Path $secrets 'relay.p12'), $certificate.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Pkcs12, $tlsPassword))
    } else {
        Write-GeneratedText "$service.crt" ($certificate.ExportCertificatePem())
        Write-GeneratedText "$service.key" ($key.ExportPkcs8PrivateKeyPem())
    }
    $certificate.Dispose()
    $issued.Dispose()
    $key.Dispose()
}
$ca.Dispose()
$caKey.Dispose()
$redisPassword = New-Secret
$databasePassword = New-Secret
$port = 8443
while ($true) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
    try { $listener.Start(); break } catch { $port++ } finally { $listener.Stop() }
}
$redisConfiguration = @"
bind 0.0.0.0
port 0
tls-port 6379
tls-cert-file /tls/redis.crt
tls-key-file /tls/redis.key
tls-ca-cert-file /tls/ca.crt
tls-auth-clients no
requirepass $redisPassword
save ""
appendonly no
maxmemory 256mb
maxmemory-policy noeviction
dir /data
loglevel warning
"@
Write-GeneratedText 'redis.conf' $redisConfiguration
$configuration = @"
PORT=$port
DATABASE_PASSWORD=$databasePassword
REDIS_PASSWORD=$redisPassword
TLS_KEYSTORE_PASSWORD=$tlsPassword
FCM_ENABLED=false
FCM_PROJECT_ID=
"@
Write-GeneratedText 'local.env' $configuration
Write-Output "Local TLS configuration generated. Certificates expire in 7 days. Relay: https://localhost:$port"
Write-Output 'Only ca.crt is public. Do not share or commit the other files in .secrets.'