# Copy this file to .env.ps1 in the same folder, then edit the two settings below.
# .env.ps1 is ignored by Git. Use your existing SQL Developer connection details.
# Service name connection: jdbc:oracle:thin:@HOST:PORT/SERVICE_NAME
# SID connection, only if SQL Developer uses SID: jdbc:oracle:thin:@HOST:PORT:SID
$env:DB_URL = 'jdbc:oracle:thin:@<HOST>:1521/<SERVICE_NAME>'
$env:DB_USERNAME = '<SCHEMA_USERNAME>'

if ($env:DB_URL.Contains('<') -or $env:DB_USERNAME.Contains('<')) {
    throw 'Edit DB_URL and DB_USERNAME in .env.ps1 using your SQL Developer connection details, then start again.'
}

# Prompt on each start. Input is hidden; the password is available to the backend
# through the process environment, not saved in this file or printed by this script.
$moneybagsOraclePassword = Read-Host 'Oracle password for the configured schema' -AsSecureString
try {
    $env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $moneybagsOraclePassword).Password
}
finally {
    $moneybagsOraclePassword.Dispose()
    Remove-Variable moneybagsOraclePassword
}

# AI provider settings are optional and separate from the Oracle connection.
# Configure them here only if you want to enable the AI assistant.
