param()
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
 . (Join-Path $projectRoot '.env.ps1')
 $classpathFile=Join-Path $projectRoot '.runtime/product-classpath.txt'
 if(-not(Test-Path -LiteralPath $classpathFile)){throw 'Run the backend test suite first to create the application test classpath.'}
 $classpath=(Get-Content -LiteralPath $classpathFile -Raw).Trim()
 & javac -cp $classpath -d .runtime tools/ProductOracleAcceptance.java
 if($LASTEXITCODE -ne 0){throw 'Oracle acceptance compilation failed.'}
 & java -cp ('.runtime;'+$classpath) ProductOracleAcceptance
 if($LASTEXITCODE -ne 0){throw 'Oracle acceptance failed. Review .runtime/product-oracle-results.txt.'}
} finally {Pop-Location}
