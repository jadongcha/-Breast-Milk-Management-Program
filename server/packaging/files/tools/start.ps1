# 서버시작.bat 가 부른다.
#   (인자 없음)   서버를 켠다. 자동 시작 작업이 이 폴더에 등록되어 있으면 그 작업으로 켜고(창을 닫아도 계속 켜져 있음),
#                 없으면 지금 창에서 실행한다(창을 닫으면 꺼짐). 관리자 권한이 없으면 UAC로 다시 실행한다.
#   /background   로그 파일(data\logs\server.log)에 기록하며 실행한다. 서버가 끝날 때까지 돌아오지 않는다(자동 시작 작업이 쓴다).
param([string]$Mode = '')
. (Join-Path $PSScriptRoot 'common.ps1')

$background = ($Mode -ieq '/background')
if (-not $background) { Ensure-Admin '서버시작.bat' $Mode }

$port = Get-ServerPort
$owner = Get-PortOwner $port
if ($owner -eq 'self') {
    Write-Host "서버가 이미 실행 중입니다. (포트 $port)"
    exit 0
}
if ($owner -eq 'other') {
    Write-Host "포트 $port 를 다른 프로그램이 쓰고 있어 서버를 켜지 않습니다."
    Write-Host '다른 폴더의 모유 서버가 켜져 있을 수 있습니다. 그 폴더에서 서버중지.bat(필요하면 자동시작해제.bat도)를 실행한 뒤 다시 시도하세요.'
    exit 1
}

if ($background) {
    New-Item -ItemType Directory -Force -Path 'data\logs' | Out-Null
    if ($script:DryRun) {
        Write-DryRun 'milkbank-server.exe serve >> data\logs\server.log 2>&1'
        exit 0
    }
    & cmd.exe /c "`"$script:Exe`" serve >> `"data\logs\server.log`" 2>&1"
    exit $LASTEXITCODE
}

if (Test-OwnTask) {
    Write-Host '자동 시작 작업으로 서버를 켭니다. (이 창을 닫아도, 로그아웃해도 서버는 계속 켜져 있습니다.)'
    if (Start-ServerTask) {
        if ($script:DryRun) { exit 0 }
        for ($n = 0; $n -lt 10; $n++) {
            Start-Sleep -Seconds 2
            if (Test-PortListening $port) {
                Write-Host "서버가 켜졌습니다. (포트 $port)"
                exit 0
            }
        }
        Write-Host '서버가 아직 응답하지 않습니다. data\logs\server.log 를 확인해 주세요.'
        exit 1
    }
    Write-Host '작업으로 켜지 못해 이 창에서 직접 켭니다.'
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
