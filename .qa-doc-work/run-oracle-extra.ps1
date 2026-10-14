$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'oracle-config.ps1')
$taskLog = Join-Path $taskProject '.runtime/oracle-m12/acceptance-extra.log'
$taskPom = Join-Path $taskProject 'backend/pom.xml'
& mvn -o -f $taskPom '-Dtest=Module12OracleTest#overlappingAndAdjacentAccessWindows+revokedPermissionsRejectStaleWork' '-Dsurefire.failIfNoSpecifiedTests=false' test *> $taskLog
if ($LASTEXITCODE -ne 0) { Write-Output 'Targeted Oracle tests failed; inspect private acceptance-extra.log and JUnit XML.'; exit 1 }
$taskXml = [xml](Get-Content -LiteralPath (Join-Path $taskProject 'backend/acceptance-tests/target/surefire-reports/TEST-com.moneybags.Module12OracleTest.xml') -Raw)
Write-Output ('Targeted Oracle: tests=' + $taskXml.testsuite.tests + ' failures=' + $taskXml.testsuite.failures + ' errors=' + $taskXml.testsuite.errors + ' skipped=' + $taskXml.testsuite.skipped)
