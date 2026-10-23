param([switch]$Apply,[switch]$VerifyRoutes)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$sessions=@{}
$required=@('TELLER_READ','TELLER_OPERATE','TELLER_APPROVE','ACCOUNT_READ','TXN_READ','TXN_POST')
function Login([string]$Name){
 $secure=Get-Content -LiteralPath (Join-Path $taskRoot '.runtime/alpha-team-credentials.dpapi') -Raw|ConvertTo-SecureString
 try{$plain=[Net.NetworkCredential]::new('', $secure).Password}finally{$secure.Dispose()}
 try{
  $rows=@($plain -split "`n"|Where-Object {($_ -split "`t")[0] -eq $Name})
  if($rows.Count -ne 1){throw 'Expected one saved demo identity.'}
  $parts=$rows[0] -split "`t"
  $body=@{username=$parts[0];password=$parts[2];clientId='MONEYBAGS_WEB'}|ConvertTo-Json
  $sessions[$Name]=(Invoke-RestMethod 'http://127.0.0.1:8091/api/v1/auth/login' -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 30).data
 }finally{$plain=$null;$rows=$null;$parts=$null;$body=$null}
}
function Api([string]$Who,[string]$Path,[string]$Method='GET',$Body=$null){
 $options=@{Uri=('http://127.0.0.1:8091/api/v1'+$Path);Method=$Method;Headers=@{Authorization='Bearer '+$sessions[$Who].accessToken};TimeoutSec=30}
 if($null -ne $Body){$options.ContentType='application/json';$options.Body=$Body|ConvertTo-Json -Depth 12}
 $result=Invoke-RestMethod @options
 if($null -ne $result -and $result.PSObject.Properties.Name -contains 'data'){return $result.data};return $result
}
try{
 foreach($name in @('admin','checker1','mcp_demo_checker')){Login $name}
 if(@($sessions.Values.user.userId|Sort-Object -Unique).Count -ne 3){throw 'Expected three distinct demo identities.'}
 $missing=@{}
 foreach($name in @('admin','checker1','mcp_demo_checker')){
  $me=Api $name '/auth/me'
  $missing[$name]=@($required|Where-Object {$_ -notin $me.permissions})
  [pscustomobject]@{username=$name;roles=$me.roles;missingTellerPermissions=$missing[$name]}|ConvertTo-Json -Compress
  if($VerifyRoutes){
   foreach($path in @('/teller/tills','/teller/cash')){
    # Empty input must fail validation before any till or money can be created.
    $probe=Invoke-WebRequest ('http://127.0.0.1:8091/api/v1'+$path) -Method Post -ContentType 'application/json' -Body '{}' -Headers @{Authorization='Bearer '+$sessions[$name].accessToken} -SkipHttpErrorCheck -TimeoutSec 30
    if($probe.StatusCode -ne 400){throw ('Expected validation-only response for '+$name+' '+$path+'; got '+$probe.StatusCode)}
    Write-Output ('Verified teller route access without posting cash: '+$name+' '+$path)
   }
  }
 }
 if(!$Apply){return}
 $targets=@('admin','checker1','mcp_demo_checker')|Where-Object {$missing[$_].Count -gt 0}
 if($targets.Count -eq 0){Write-Output 'All demo bank staff already have teller access.';return}
 $roles=@(Api 'admin' '/iam/roles')
 $role=@($roles|Where-Object roleCode -eq 'TELLER_OPERATIONS')
 if($role.Count -gt 1){throw 'Ambiguous teller role.'}
 $permissions=@(Api 'admin' '/iam/permissions')
 $permissionIds=@($permissions|Where-Object {$_.permissionCode -in $required}|ForEach-Object permissionId)
 if($permissionIds.Count -ne $required.Count){throw 'The teller permission catalog is incomplete.'}
 if($role.Count -eq 0){
  $role=@(Api 'admin' '/iam/roles' 'POST' @{roleCode='TELLER_OPERATIONS';displayName='Teller operations';sensitive=$true;permissionIds=$permissionIds})
 }else{
  $detail=Api 'admin' ('/iam/roles/'+$role[0].roleId)
  if(@($permissionIds|Where-Object {$_ -notin $detail.permissions.permissionId}).Count -gt 0){throw 'Existing teller role lacks required permissions; inspect it before changing its grants.'}
 }
 foreach($name in $targets){
  # The maker and checker must both differ from each other; the checker must also differ from the target.
  $approver=if($name -eq 'mcp_demo_checker'){'checker1'}else{'mcp_demo_checker'}
  $request=Api 'admin' '/iam/access-requests' 'POST' @{targetUserId=$sessions[$name].user.userId;roleId=$role[0].roleId;changeType='GRANT';scopeType='GLOBAL';validFrom=[DateTimeOffset]::UtcNow.AddSeconds(-5).ToString('o');reason='User requested teller cash duties for admin and checker demo staff.';dimensions=@()}
  $null=Api $approver ('/iam/access-requests/'+$request.requestId+'/decision') 'POST' @{rowVersion=$request.rowVersion;decision='APPROVED'}
  Login $name
  $me=Api $name '/auth/me'
  if(@($required|Where-Object {$_ -notin $me.permissions}).Count -gt 0){throw 'Teller access verification failed.'}
  Write-Output ('Verified teller access: '+$name)
 }
}finally{
 foreach($name in @($sessions.Keys)){try{$null=Api $name '/auth/logout' 'POST' @{}}catch{}}
 $sessions.Clear()
}
