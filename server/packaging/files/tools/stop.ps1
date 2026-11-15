# 서버중지.bat 가 부른다.
. (Join-Path $PSScriptRoot 'common.ps1')

if ($script:DryRun) {
    Write-DryRun 'taskkill /IM milkbank-server.exe /F'
    exit 0
}

& taskkill /IM milkbank-server.exe /F
if ($LASTEXITCODE -ne 0) {
    Write-Host ''
    Write-Host '서버를 끄지 못했습니다. 이미 꺼져 있거나, 자동 시작으로 켜진 서버라면'
    Write-Host '이 파일을 마우스 오른쪽 → 관리자 권한으로 실행해 주세요.'
    exit 1
}
Write-Host ''
Write-Host '서버를 껐습니다. 다시 켜려면 서버시작.bat를 실행하세요.'
exit 0
