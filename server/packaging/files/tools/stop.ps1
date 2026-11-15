# 서버중지.bat 가 부른다.
. (Join-Path $PSScriptRoot 'common.ps1')

Ensure-Admin '서버중지.bat'

# 이 PC에서 도는 milkbank-server.exe 를 모두 끈다(다른 폴더의 서버도 포함).
Write-Host '이 PC에서 켜져 있는 모든 milkbank-server.exe(다른 폴더의 서버 포함)를 끕니다.'
if ($script:DryRun) {
    Write-DryRun 'taskkill /IM milkbank-server.exe /F'
    exit 0
}

& taskkill /IM milkbank-server.exe /F
if ($LASTEXITCODE -ne 0) {
    Write-Host ''
    Write-Host '서버를 끄지 못했습니다. 이미 꺼져 있을 수 있습니다.'
    exit 1
}
Write-Host ''
Write-Host '서버를 껐습니다. 다시 켜려면 서버시작.bat를 실행하세요.'
exit 0
