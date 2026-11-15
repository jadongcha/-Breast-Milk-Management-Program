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

# 폴더를 잠근다: 관리자와 SYSTEM만 바꿀 수 있고, 일반 사용자는 프로그램 파일을 읽고 실행만 할 수 있다.
# data 폴더(DB, 개인키, 백업)는 관리자와 SYSTEM만 접근한다. SYSTEM 권한으로 도는 자동 시작 작업이
# 실행하는 파일을 일반 사용자가 고쳐 SYSTEM 권한을 얻는 일을 막는다.
function Protect-PackageFolder {
    $root = $script:Root
    $data = Join-Path $root 'data'
    $qrSrc = Join-Path $data 'tls\server-qr.png'
    $qrDst = Join-Path $root '서버등록QR.png'
    # SID로 지정해 Windows 언어와 상관없이 동작한다. S-1-5-18 SYSTEM, S-1-5-32-544 Administrators, S-1-5-32-545 Users
    # 먼저 소유자를 Administrators로 바꾼다. 일반 사용자가 폴더를 만들었거나 복사했다면 그 사람이 소유자라서
    # 권한 변경(WRITE_DAC)을 암묵적으로 갖고 있어, 스스로 다시 쓰기 권한을 줄 수 있기 때문이다.
    $cmds = @(
        @($root, '/setowner', '*S-1-5-32-544', '/T'),
        @($root, '/inheritance:r'),
        @($root, '/grant:r', '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F', '*S-1-5-32-545:(OI)(CI)RX'),
        @($data, '/inheritance:r'),
        @($data, '/grant:r', '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F')
    )
    if ($script:DryRun) {
        Write-DryRun "copy data\tls\server-qr.png -> 서버등록QR.png"
        foreach ($c in $cmds) { Write-DryRun ('icacls "' + ($c -join '" "') + '"') }
        return $true
    }
    if (Test-Path -LiteralPath $qrSrc) { Copy-Item -LiteralPath $qrSrc -Destination $qrDst -Force }
    if (-not (Test-Path -LiteralPath $data)) { New-Item -ItemType Directory -Path $data | Out-Null }
    foreach ($c in $cmds) {
        & icacls @c *> $null
        if ($LASTEXITCODE -ne 0) {
            Write-Host "폴더 권한을 잠그지 못했습니다: icacls $($c -join ' ')"
            return $false
        }
    }
    Write-Host '폴더를 잠갔습니다.'
    Write-Host "  - $root : 소유자는 Administrators, 관리자와 SYSTEM은 모든 권한, 일반 사용자는 읽기와 실행만 가능합니다."
    Write-Host "  - $data : 관리자와 SYSTEM만 접근할 수 있습니다(일반 사용자는 읽을 수 없습니다)."
    Write-Host '  - 서버 등록 QR은 일반 사용자도 열 수 있도록 서버등록QR.png로 폴더 맨 위에 복사했습니다.'
    return $true
}

# PC가 켜질 때 서버가 저절로 시작되도록 작업 스케줄러에 등록한다.
function Register-AutoStart {
    $bat = Join-Path $script:Root '서버시작.bat'
    $schtasksArgs = '/Create /TN "{0}" /TR "\"{1}\" /background" /SC ONSTART /RU SYSTEM /RL HIGHEST /F' -f $script:TaskName, $bat
    if ($script:DryRun) {
        Write-DryRun "schtasks $schtasksArgs"
        Write-DryRun '작업 설정: 실행 시간 제한 없음, 배터리에서도 시작'
        return $true
    }
    $p = Start-Process -FilePath schtasks -ArgumentList $schtasksArgs -Wait -NoNewWindow -PassThru
    if ($p.ExitCode -ne 0) {
        Write-Host '자동 시작을 등록하지 못했습니다. 서버는 서버시작.bat로 직접 켜야 합니다.'
        return $false
    }
    # 기본값은 3일 뒤 작업을 끝내고 배터리에서는 시작하지 않으므로 바꾸고, 바뀌었는지 확인한다.
    try {
        $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
        Set-ScheduledTask -TaskName $script:TaskName -Settings $settings -ErrorAction Stop | Out-Null
        $now = (Get-ScheduledTask -TaskName $script:TaskName -ErrorAction Stop).Settings
        if ($now.ExecutionTimeLimit -ne 'PT0S' -or $now.DisallowStartIfOnBatteries) { throw '설정이 적용되지 않았습니다.' }
    } catch {
        Write-Host '*** 자동 시작은 등록했지만 작업 설정을 바꾸지 못했습니다. ***'
        Write-Host "    원인: $($_.Exception.Message)"
        Write-Host '    이대로 두면 서버가 3일 뒤 저절로 멈추거나, 노트북이 배터리일 때 시작되지 않을 수 있습니다.'
        Write-Host '    작업 스케줄러에서 MilkbankServer 작업의 "다음 시간 이상 실행되면 중지"를 끄고'
        Write-Host '    "배터리 사용 중이면 시작 안 함"을 꺼 주세요.'
        return $false
    }
    Write-Host '자동 시작을 등록했습니다(실행 시간 제한 없음, 배터리에서도 시작).'
    return $true
}
