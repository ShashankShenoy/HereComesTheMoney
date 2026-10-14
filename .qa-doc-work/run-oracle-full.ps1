$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'oracle-config.ps1')
$taskLog = Join-Path $taskProject '.runtime/oracle-m12/acceptance-40.log'
$taskPom = Join-Path $taskProject 'backend/pom.xml'
& mvn -o -f $taskPom '-Dtest=Module12OracleTest' '-Dsurefire.failIfNoSpecifiedTests=false' test *> $taskLog
if ($LASTEXITCODE -ne 0) { Write-Output 'Full Oracle suite failed; inspect private acceptance-40.log and JUnit XML.'; exit 1 }
$taskSourceXml = Join-Path $taskProject 'backend/acceptance-tests/target/surefire-reports/TEST-com.moneybags.Module12OracleTest.xml'
$taskXml = [xml](Get-Content -LiteralPath $taskSourceXml -Raw)
if ($taskXml.testsuite.tests -ne '40' -or $taskXml.testsuite.failures -ne '0' -or $taskXml.testsuite.errors -ne '0' -or $taskXml.testsuite.skipped -ne '0') { throw 'Unexpected Oracle full-suite results.' }
Copy-Item -LiteralPath $taskSourceXml -Destination (Join-Path $taskProject '.runtime/oracle-m12/backend-final.xml') -Force
Write-Output ('Full Oracle: tests=' + $taskXml.testsuite.tests + ' failures=' + $taskXml.testsuite.failures + ' errors=' + $taskXml.testsuite.errors + ' skipped=' + $taskXml.testsuite.skipped)
