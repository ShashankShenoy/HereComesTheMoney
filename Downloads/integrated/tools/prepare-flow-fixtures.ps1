$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$runtime=Join-Path $taskRoot '.runtime/flow-fixtures'
New-Item -ItemType Directory -Force -Path (Join-Path $runtime 'classes')|Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead((Join-Path $taskRoot 'backend/local-database/target/local-database-1.0.0-exec.jar'))
try{
  $entries=@($archive.Entries|Where-Object{$_.FullName -like 'BOOT-INF/lib/h2-*.jar'})
  if($entries.Count -ne 1){throw 'Expected exactly one H2 dependency in the local database executable.'}
  [IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0],(Join-Path $runtime 'h2.jar'),$true)
}finally{$archive.Dispose()}
& javac --release 17 -encoding UTF-8 -d (Join-Path $runtime 'classes') (Join-Path $PSScriptRoot 'LocalFlowFixtures.java')
if($LASTEXITCODE -ne 0){throw 'Local flow fixture compilation failed.'}
