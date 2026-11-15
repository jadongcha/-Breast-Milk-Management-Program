# 배치 파일(처음설정.bat 등)이 부르는 공통 함수. 직접 실행하지 않는다.
# MILKBANK_DRYRUN=1 이면 작업 스케줄러, 서버 종료, 서버 실행, QR 열기 대신 명령만 보여 준다(시험용).
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$script:Root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $script:Root
$script:Exe = Join-Path $script:Root 'milkbank-server.exe'
$script:DryRun = ($env:MILKBANK_DRYRUN -eq '1')
$script:TaskName = 'MilkbankServer'

function Test-Admin {
    & net session *> $null
    return ($LASTEXITCODE -eq 0)
}

function Get-ServerPort {
    $port = 8443
    try {
        $info = Get-Content -Raw -Encoding UTF8 -ErrorAction Stop -LiteralPath (Join-Path $script:Root 'data\tls\server.json') | ConvertFrom-Json
        $port = [int]$info.port
    } catch { }
    return $port
}

function Test-PortListening([int]$Port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

function Write-DryRun([string]$Text) {
    Write-Host "[드라이런] $Text"
}
