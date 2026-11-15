# 자동시작해제.bat 가 부른다.
. (Join-Path $PSScriptRoot 'common.ps1')

if ($script:DryRun) {
    Write-DryRun "schtasks /Delete /TN `"$script:TaskName`" /F"
    exit 0
}

if (-not (Test-Admin)) {
    Write-Host '이 파일을 마우스 오른쪽 → 관리자 권한으로 실행해 주세요.'
    exit 1
}

& schtasks /Delete /TN $script:TaskName /F
if ($LASTEXITCODE -ne 0) {
    Write-Host ''
    Write-Host '자동 시작 등록을 지우지 못했습니다. 등록되어 있지 않을 수 있습니다.'
    exit 1
}
Write-Host ''
Write-Host 'PC가 켜질 때 서버가 저절로 시작되지 않도록 지웠습니다.'
Write-Host '지금 켜져 있는 서버는 그대로이니, 끄려면 서버중지.bat를 실행하세요.'
exit 0
