#requires -Version 7.0
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'tools/oracle-runtime.psm1') -Force
Stop-OracleServices -ProjectRoot $PSScriptRoot
