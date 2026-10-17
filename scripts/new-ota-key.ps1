#requires -Version 7.4
<#
Creates the key that signs over-the-air settings (see docs/DEPLOYMENT-GUIDE.md, "Push settings over the air").

The private half is written under .secrets\ota-signing, which is never committed, uploaded or packaged, and which this script restricts to the
current Windows user, every time it runs and not only when it creates the folder; -RestrictOnly does just that, for a folder that exists
already, and creates no key. The restriction is checked afterwards, and the script stops if anybody else could still reach the folder.
The key is encrypted with a generated password that is stored next to it (the same arrangement as the Android signing key):
that keeps a stray copy of the .pem alone useless, but anyone who can read the whole folder can sign settings, so the folder has to be
protected and backed up as a whole, and the backup of the password is best kept apart from the backup of the key. Without the key no further
settings can be published until a new app version carries a new public key. The public half is added
to android\app\src\main\res\raw\ota_keys.txt, which the app embeds; the app accepts settings signed by the keys listed there and by
nothing else. Up to three keys may be listed, so a new key can be shipped before the old one is retired.
#>
[CmdletBinding()]
param(
    [ValidatePattern('\A[A-Za-z0-9_-]{1,16}\z')][string]$KeyId,
    [switch]$RestrictOnly
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$directory = Join-Path $root '.secrets\ota-signing'
$keysFile = Join-Path $root 'android\app\src\main\res\raw\ota_keys.txt'

# Set-Acl cannot change a folder that exists already (it needs a privilege an ordinary user lacks), icacls can: the inherited entries are
# removed, the current user is given full control (by SID, so that the account's name does not matter) and every other entry set on the item
# itself is removed too. This is done for the folder and for each file in it (a file restored from a backup can bring entries of its own), and
# then what is left is read back, so that "restricted" is something that was seen.
function Get-OtherEntries([string]$Path, $Identity) {
    return @((Get-Acl -LiteralPath $Path).Access | Where-Object { $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value -ne $Identity.User.Value })
}

function Protect-KeyItem([string]$Path, $Identity, [bool]$IsFolder) {
    $grant = if ($IsFolder) { '*{0}:(OI)(CI)F' -f $Identity.User.Value } else { '*{0}:F' -f $Identity.User.Value }
    & icacls.exe $Path /inheritance:r /grant:r $grant | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not restrict '$Path' to the current user (icacls exit code $LASTEXITCODE)." }
    foreach ($sid in (Get-OtherEntries $Path $Identity | ForEach-Object { $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value } | Sort-Object -Unique)) {
        & icacls.exe $Path /remove "*$sid" | Out-Null
    }
}

function Protect-KeyFolder([string]$Path, [string]$Consequence = 'No key was created.') {
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
    if (-not $IsWindows) {
        [IO.File]::SetUnixFileMode($Path, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::UserExecute)
        foreach ($file in Get-ChildItem -LiteralPath $Path -File -Force) { [IO.File]::SetUnixFileMode($file.FullName, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite) }
        return
    }
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    Protect-KeyItem $Path $identity $true
    foreach ($file in Get-ChildItem -LiteralPath $Path -File -Force) { Protect-KeyItem $file.FullName $identity $false }
    $failures = [System.Collections.Generic.List[string]]::new()
    $folderOthers = Get-OtherEntries $Path $identity
    if (-not (Get-Acl -LiteralPath $Path).AreAccessRulesProtected -or $folderOthers.Count -gt 0) {
        $failures.Add("the folder can still be reached by: $((($folderOthers | ForEach-Object { $_.IdentityReference.Value }) | Sort-Object -Unique) -join ', ') (or its inheritance is not blocked)")
    }
    foreach ($file in Get-ChildItem -LiteralPath $Path -File -Force) {
        $fileOthers = Get-OtherEntries $file.FullName $identity
        if ($fileOthers.Count -gt 0) { $failures.Add("$($file.Name) can still be reached by: $((($fileOthers | ForEach-Object { $_.IdentityReference.Value }) | Sort-Object -Unique) -join ', ')") }
    }
    if ($failures.Count -gt 0) { throw "The key folder '$Path' is not restricted to the current user: $($failures -join '; '). $Consequence" }
}

Protect-KeyFolder $directory
if ($RestrictOnly) {
    Write-Output "The key folder $directory is restricted to the current user. No key was created."
    return
}
New-Item -ItemType Directory -Path (Split-Path $keysFile -Parent) -Force | Out-Null
$existing = if (Test-Path -LiteralPath $keysFile) { @(Get-Content -LiteralPath $keysFile | Where-Object { $_ -and -not $_.StartsWith('#') }) } else { @() }
if ($existing.Count -ge 3) { throw 'Three public keys are already listed; retire one before adding another.' }

$ecdsa = [System.Security.Cryptography.ECDsa]::Create([System.Security.Cryptography.ECCurve]::CreateFromFriendlyName('nistP256'))
try {
    $publicKey = $ecdsa.ExportSubjectPublicKeyInfo()
    if (-not $KeyId) {
        $digest = [System.Security.Cryptography.SHA256]::HashData($publicKey)
        $KeyId = 'k' + (($digest[0..5] | ForEach-Object { $_.ToString('x2') }) -join '')
    }
    if ($existing | Where-Object { $_.Split(' ')[0] -ceq $KeyId }) { throw 'That key ID is already listed.' }
    $privatePath = Join-Path $directory "ota-$KeyId.pem"
    $passwordPath = Join-Path $directory "ota-$KeyId.password.txt"
    if ((Test-Path -LiteralPath $privatePath) -or (Test-Path -LiteralPath $passwordPath)) { throw 'A key with that ID already exists; nothing was overwritten.' }
    $password = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(33))
    $parameters = [System.Security.Cryptography.PbeParameters]::new([System.Security.Cryptography.PbeEncryptionAlgorithm]::Aes256Cbc,
        [System.Security.Cryptography.HashAlgorithmName]::SHA256, 200000)
    [IO.File]::WriteAllText($privatePath, $ecdsa.ExportEncryptedPkcs8PrivateKeyPem($password, $parameters) + "`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText($passwordPath, $password, [Text.UTF8Encoding]::new($false))
    $line = "$KeyId $([Convert]::ToBase64String($publicKey))"
    if (-not (Test-Path -LiteralPath $keysFile)) {
        [IO.File]::WriteAllText($keysFile, "# Public keys that may sign over-the-air settings: key ID, then the base64 X.509 SubjectPublicKeyInfo of a P-256 key.`n# At most three. Created by scripts/new-ota-key.ps1; the private halves are never committed.`n", [Text.UTF8Encoding]::new($false))
    }
    [IO.File]::AppendAllText($keysFile, $line + "`n", [Text.UTF8Encoding]::new($false))
} finally { $ecdsa.Dispose() }
# The key files exist now: restrict them as well and check once more, so that what was written is what was protected. If this fails the key
# does exist (the only place its generated id was to be printed is the line after this), so the message has to say so.
Protect-KeyFolder $directory "The signing key '$KeyId' WAS created: its files are in the folder and its public half is in ota_keys.txt. Fix the cause and run new-ota-key.ps1 -RestrictOnly before relying on the key."
Write-Output "Created signing key '$KeyId'."
Write-Output "Private key (encrypted) and its password: $directory  -- back this folder up offline as a whole; never commit or upload it."
Write-Output "Public key added to: $keysFile  -- it takes effect in the next app build."
