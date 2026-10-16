function Get-AlphaTeamKey {
  param([string]$Passphrase, [byte[]]$Salt, [int]$Iterations)
  $passphraseBytes = [System.Text.Encoding]::UTF8.GetBytes($Passphrase)
  try {
    return [System.Security.Cryptography.Rfc2898DeriveBytes]::Pbkdf2(
      $passphraseBytes, $Salt, $Iterations,
      [System.Security.Cryptography.HashAlgorithmName]::SHA256, 32)
  } finally {
    [Array]::Clear($passphraseBytes, 0, $passphraseBytes.Length)
  }
}

function Protect-AlphaTeamPayload {
  param([string]$PlainJson, [string]$Passphrase)
  $salt = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(16)
  $nonce = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(12)
  $iterations = 310000
  $key = Get-AlphaTeamKey $Passphrase $salt $iterations
  $plain = [System.Text.Encoding]::UTF8.GetBytes($PlainJson)
  $cipher = [byte[]]::new($plain.Length)
  $tag = [byte[]]::new(16)
  try {
    $aes = [System.Security.Cryptography.AesGcm]::new($key, 16)
    try { $aes.Encrypt($nonce, $plain, $cipher, $tag) }
    finally { $aes.Dispose() }
    return [ordered]@{
      format = 'moneybags-alpha-team-v1'
      kdf = 'PBKDF2-HMAC-SHA256'
      iterations = $iterations
      cipher = 'AES-256-GCM'
      salt = [Convert]::ToBase64String($salt)
      nonce = [Convert]::ToBase64String($nonce)
      ciphertext = [Convert]::ToBase64String($cipher)
      tag = [Convert]::ToBase64String($tag)
    }
  } finally {
    [Array]::Clear($key, 0, $key.Length)
    [Array]::Clear($plain, 0, $plain.Length)
  }
}

function Unprotect-AlphaTeamPayload {
  param($Bundle, [string]$Passphrase)
  if ($Bundle.format -ne 'moneybags-alpha-team-v1' -or
      $Bundle.kdf -ne 'PBKDF2-HMAC-SHA256' -or
      $Bundle.cipher -ne 'AES-256-GCM' -or
      [int]$Bundle.iterations -lt 210000) {
    throw 'Unsupported or weakened ALPHA team bundle format.'
  }
  $salt = [Convert]::FromBase64String($Bundle.salt)
  $nonce = [Convert]::FromBase64String($Bundle.nonce)
  $cipher = [Convert]::FromBase64String($Bundle.ciphertext)
  $tag = [Convert]::FromBase64String($Bundle.tag)
  if ($salt.Length -ne 16 -or $nonce.Length -ne 12 -or $tag.Length -ne 16) {
    throw 'Invalid ALPHA team bundle parameters.'
  }
  $key = Get-AlphaTeamKey $Passphrase $salt ([int]$Bundle.iterations)
  $plain = [byte[]]::new($cipher.Length)
  try {
    $aes = [System.Security.Cryptography.AesGcm]::new($key, 16)
    try { $aes.Decrypt($nonce, $cipher, $tag, $plain) }
    catch [System.Security.Cryptography.AuthenticationTagMismatchException] {
      throw 'The team passphrase is incorrect or the bundle was changed.'
    }
    finally { $aes.Dispose() }
    return [System.Text.Encoding]::UTF8.GetString($plain)
  } finally {
    [Array]::Clear($key, 0, $key.Length)
    [Array]::Clear($plain, 0, $plain.Length)
  }
}

Export-ModuleMember -Function Protect-AlphaTeamPayload, Unprotect-AlphaTeamPayload
