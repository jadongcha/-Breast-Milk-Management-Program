# 서버시작.bat 가 부른다.
#   (인자 없음)   지금 창에서 서버를 실행한다.
#   /background   로그 파일(data\logs\server.log)에 기록하며 실행한다. 서버가 끝날 때까지 돌아오지 않는다.
param([string]$Mode = '')
. (Join-Path $PSScriptRoot 'common.ps1')

$port = Get-ServerPort
if (Test-PortListening $port) {
    Write-Host "서버가 이미 실행 중입니다. (포트 $port)"
    exit 0
}

if ($Mode -ieq '/background') {
    New-Item -ItemType Directory -Force -Path 'data\logs' | Out-Null
    if ($script:DryRun) {
        Write-DryRun 'milkbank-server.exe serve >> data\logs\server.log 2>&1'
        exit 0
    }
    & cmd.exe /c "`"$script:Exe`" serve >> `"data\logs\server.log`" 2>&1"
    exit $LASTEXITCODE
}

Write-Host '서버를 시작합니다. 이 창을 닫으면 서버가 꺼집니다.'
if ($script:DryRun) {
    Write-DryRun 'milkbank-server.exe serve'
    exit 0
}
& $script:Exe serve
$rc = $LASTEXITCODE
Write-Host ''
Write-Host '서버가 끝났습니다. 위 메시지를 확인해 주세요.'
exit $rc
