# 배치 파일(처음설정.bat 등)이 부르는 공통 함수. 직접 실행하지 않는다.
# MILKBANK_DRYRUN=1 이면 작업 스케줄러, 서버 종료, 서버 실행, QR 열기 대신 명령만 보여 준다(시험용).
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$script:Root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $script:Root
$script:Exe = Join-Path $script:Root 'milkbank-server.exe'
$script:DryRun = ($env:MILKBANK_DRYRUN -eq '1')
$script:TaskName = 'MilkbankServer'

# 관리자 권한(관리자 그룹 소속 + 권한 상승된 상태)인지 확인한다. SYSTEM(자동 시작 작업)도 통과한다.
function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    if ($id.User.Value -eq 'S-1-5-18') { return $true }
    return ([Security.Principal.WindowsPrincipal]$id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# 관리자가 아니면 UAC("사용자 계정 컨트롤") 창을 띄워 같은 .bat 파일을 관리자 권한으로 다시 실행하고 이 스크립트는 끝낸다.
# 새 창이 끝날 때까지 기다리고, .bat 파일은 이 종료 코드(77)를 보면 "계속하려면" 멈춤을 건너뛴다.
$script:RelaunchCode = 77
function Ensure-Admin([string]$Bat, [string]$BatArgs = '') {
    if (Test-Admin) { return }
    $batPath = Join-Path $script:Root $Bat
    $cmdArgs = '/c ""{0}" {1}"' -f $batPath, $BatArgs
    if ($script:DryRun) {
        Write-DryRun "현재 관리자 권한이 아니므로 UAC로 다시 실행하고 이 창은 끝냅니다: Start-Process cmd.exe -Verb RunAs -Wait -ArgumentList '$cmdArgs'"
        return
    }
    Write-Host '관리자 권한이 필요합니다. 곧 나오는 "사용자 계정 컨트롤" 창에서 "예"를 눌러 주세요.'
    try {
        Start-Process -FilePath cmd.exe -ArgumentList $cmdArgs -WorkingDirectory $script:Root -Verb RunAs -Wait -ErrorAction Stop
    } catch {
        Write-Host ''
        Write-Host '관리자 권한을 허용하지 않아 끝냅니다.'
        Write-Host '다시 실행해서 "예"를 누르거나, 파일을 마우스 오른쪽 → 관리자 권한으로 실행해 주세요.'
        exit 1
    }
    exit $script:RelaunchCode
}

# 이 폴더가 사용자 프로필(C:\Users\...: 바탕화면, 다운로드 등)이나 OneDrive 안에 있으면 $true.
# 그런 곳은 폴더 잠금이 소용없고(상위 폴더의 주인이 폴더를 통째로 바꿔치기할 수 있다) 동기화·정리로 파일이 사라질 수 있다.
function Test-UnsafeLocation {
    $r = $script:Root.TrimEnd('\') + '\'
    $ic = [StringComparison]::OrdinalIgnoreCase
    $bases = @((Join-Path $env:SystemDrive 'Users'))
    if ($env:USERPROFILE) { $bases += (Split-Path -Parent $env:USERPROFILE) }
    if ($env:OneDrive) { $bases += $env:OneDrive }
    foreach ($b in $bases) {
        if ($b -and $r.StartsWith($b.TrimEnd('\') + '\', $ic)) { return $true }
    }
    return ($r -match '(?i)\\OneDrive[^\\]*\\')
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

# 포트를 쓰는 프로그램이 누구인지: $null(비어 있음), 'self'(이 폴더의 서버), 'other'(그 밖의 프로그램, 다른 폴더의 서버 포함)
function Get-PortOwner([int]$Port) {
    $conn = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    if ($conn.Count -eq 0) { return $null }
    foreach ($cn in $conn) {
        $p = Get-Process -Id $cn.OwningProcess -ErrorAction SilentlyContinue
        if ($p -and $p.Path -and ($p.Path -ieq $script:Exe)) { return 'self' }
    }
    return 'other'
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

# 자동 시작 작업이 등록되어 있고 이 폴더의 서버시작.bat를 가리키는지. 다른 폴더를 가리키는 작업을 실행하면 엉뚱한 서버가 켜진다.
# (시험용 MILKBANK_DRYRUN_TASK=1 이면 있는 것으로 본다. 조회만 하고 등록·실행은 하지 않는다.)
function Test-OwnTask {
    if ($script:DryRun -and $env:MILKBANK_DRYRUN_TASK -eq '1') { return $true }
    try {
        $t = Get-ScheduledTask -TaskName $script:TaskName -ErrorAction Stop
        $bat = Join-Path $script:Root '서버시작.bat'
        foreach ($a in $t.Actions) {
            if ((($a.Execute + ' ' + $a.Arguments).IndexOf($bat, [StringComparison]::OrdinalIgnoreCase)) -ge 0) { return $true }
        }
    } catch { }
    return $false
}

# 작업 스케줄러 작업으로 서버를 켠다. SYSTEM 권한으로 돌아서 로그아웃해도 꺼지지 않고, 방화벽 허용 창도 뜨지 않는다.
function Start-ServerTask {
    if ($script:DryRun) {
        Write-DryRun "schtasks /Run /TN `"$script:TaskName`""
        return $true
    }
    & schtasks /Run /TN $script:TaskName *> $null
    return ($LASTEXITCODE -eq 0)
}

# 폴더 안 파일의 "인터넷에서 받은 파일" 표시(차단)를 지운다. 안 지우면 더블클릭이나 시작할 때 보안 경고가 뜬다.
function Unblock-Package {
    if ($script:DryRun) {
        Write-DryRun "Get-ChildItem -LiteralPath `"$script:Root`" -Recurse -Force -File | Unblock-File"
        return
    }
    Get-ChildItem -LiteralPath $script:Root -Recurse -Force -File -ErrorAction SilentlyContinue | Unblock-File -ErrorAction SilentlyContinue
}

# 서버가 응답할 때까지 기다리며 /health 를 확인한다(자체 서명 인증서라 -k, PowerShell 5.1 웹 요청 대신 curl.exe).
function Wait-ServerHealth([string]$Addr, [int]$Port) {
    for ($n = 0; $n -lt 6; $n++) {
        Start-Sleep -Seconds 3
        $code = (& curl.exe -k -s -o NUL -w '%{http_code}' --max-time 5 "https://${Addr}:${Port}/health" 2>$null)
        if ($code -eq '200') { return $true }
    }
    return $false
}
