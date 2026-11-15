# 처음설정.bat 가 부른다. 인증서, 관리자 계정, 자동 시작 등록, 서버 시작까지 한 번에 한다.
. (Join-Path $PSScriptRoot 'common.ps1')

# 사용자 프로필(바탕화면, 다운로드 등)이나 OneDrive 안에서는 설정하지 않는다. 폴더 잠금이 소용없고 파일이 사라질 수 있다.
if (Test-UnsafeLocation) {
    Write-Host '이 폴더는 사용자 프로필(C:\Users\...: 바탕화면, 다운로드, 문서 등) 또는 OneDrive 안에 있어 설정할 수 없습니다.'
    Write-Host "  현재 위치: $script:Root"
    Write-Host '  그런 곳은 폴더를 잠가도 그 사용자가 폴더를 통째로 바꿔치기할 수 있고, 동기화나 정리로 파일이 사라질 수 있습니다.'
    Write-Host '  폴더를 C:\MilkbankServer 처럼 C:\ 바로 아래(또는 C:\Program Files)로 옮긴 뒤 처음설정.bat를 다시 실행해 주세요.'
    if ($script:DryRun -and $env:MILKBANK_DRYRUN_SKIP_LOCATION -eq '1') {
        Write-DryRun '(시험용: 위치 검사 결과를 무시하고 계속합니다)'
    } else {
        exit 1
    }
}

Ensure-Admin '처음설정.bat'

Write-Host '폴더 안 파일의 "인터넷에서 받은 파일" 차단 표시를 지웁니다.'
Unblock-Package

Write-Host '============================================'
Write-Host '  모유 관리 서버 처음 설정'
Write-Host '============================================'
Write-Host ''

# 같은 포트를 다른 서버(다른 폴더의 서버 등)가 쓰고 있으면, 그 서버를 이 폴더의 서버로 착각하지 않도록 여기서 멈춘다.
function Stop-IfPortTaken([int]$Port) {
    if ((Get-PortOwner $Port) -eq 'other') {
        Write-Host ''
        Write-Host "포트 $Port 를 이미 다른 프로그램이 쓰고 있어 설정을 멈춥니다."
        Write-Host '다른 폴더의 모유 서버(예: 데모 폴더나 이전 버전)가 켜져 있을 가능성이 큽니다. 그대로 두면 새 서버가 켜지지 않고,'
        Write-Host '핸드폰은 이전 서버를 찾아가 인증 확인에 실패합니다.'
        Write-Host '그 폴더에서 서버중지.bat 와 자동시작해제.bat 를 실행한 뒤 처음설정.bat를 다시 실행해 주세요.'
        Write-Host '(서버중지.bat는 이 PC에서 켜져 있는 모든 milkbank-server.exe를 끕니다.)'
        exit 1
    }
}

# 서버를 켜고 응답을 확인한다. 자동 시작 작업이 등록됐으면 그 작업으로 켠다(SYSTEM 권한이라 로그아웃해도 안 꺼지고 방화벽 허용 창도 안 뜬다).
function Start-ServerAndCheck([string]$Addr, [int]$Port, [bool]$Registered) {
    if ((Get-PortOwner $Port) -eq 'self') {
        Write-Host "서버가 이미 켜져 있습니다. (포트 $Port)"
    } else {
        $started = $false
        if ($Registered) {
            Write-Host '자동 시작 작업으로 서버를 켭니다.'
            $started = Start-ServerTask
            if (-not $started) { Write-Host '작업으로 켜지 못했습니다. 이 창의 사용자 권한으로 서버를 켭니다(로그아웃하면 꺼집니다).' }
        }
        if (-not $started) {
            if ($script:DryRun) {
                Write-DryRun '서버시작.bat /background 를 숨긴 창으로 실행'
            } else {
                Start-Process -FilePath (Join-Path $script:Root '서버시작.bat') -ArgumentList '/background' -WindowStyle Hidden
            }
        }
    }
    if ($script:DryRun) { return }
    if (Wait-ServerHealth $Addr $Port) {
        Write-Host "서버가 잘 켜졌습니다. (https://${Addr}:${Port}/health 확인 결과: 200)"
    } else {
        Write-Host '서버가 아직 응답하지 않습니다.'
        Write-Host 'data\logs\server.log 를 확인하거나 서버시작.bat를 직접 실행해 보세요.'
    }
}

# 선택한 IP가 속한 네트워크의 종류(공용/개인/도메인)를 보여 주고, 공용이면 바꾸는 방법을 안내한다(바꾸지는 않는다).
function Show-NetworkCategory([string]$Addr) {
    try {
        $alias = (Get-NetIPAddress -IPAddress $Addr -AddressFamily IPv4 -ErrorAction Stop | Select-Object -First 1).InterfaceAlias
        $np = Get-NetConnectionProfile -InterfaceAlias $alias -ErrorAction Stop | Select-Object -First 1
        $cat = [string]$np.NetworkCategory
    } catch {
        Write-Host '이 주소의 네트워크 종류(공용/개인/도메인)를 확인하지 못했습니다. 아래 방화벽 안내를 읽고 전산팀에 문의하세요.'
        return
    }
    $label = switch ($cat) { 'Public' { '공용' } 'Private' { '개인' } 'DomainAuthenticated' { '도메인' } default { $cat } }
    Write-Host "이 주소의 네트워크: $($np.Name) [$alias]  종류: $label"
    if ($cat -eq 'Public') {
        Write-Host '*** 네트워크 종류가 "공용"입니다. 공용에서는 Windows가 외부 접속을 막아 핸드폰이 서버에 접속하지 못할 수 있습니다. ***'
        Write-Host '  바꾸는 방법: 설정 → 네트워크 및 인터넷 → 이더넷(또는 Wi-Fi) → 이 연결의 속성 → 네트워크 프로필 유형을 "개인"으로 선택'
        Write-Host '  병원 도메인에 연결된 PC라면 네트워크 종류는 전산팀이 정하므로 전산팀에 문의하세요.'
    }
}

# 이미 설정되어 있으면 무엇을 할지 묻는다
$reset = $false
if (Test-Path -LiteralPath 'data\tls\server.json') {
    Write-Host '이미 설정되어 있습니다.'
    Write-Host '  [1] 자동 시작만 다시 등록하고 서버 켜기 (인증서는 그대로, 기기는 QR을 다시 찍지 않아도 됩니다)'
    Write-Host '  [2] 처음부터 다시 설정 (IP를 바꿀 때 고릅니다. IP가 바뀌면 모든 기기에서 QR을 다시 찍어야 합니다)'
    Write-Host '  [N] 취소'
    $choice = Read-Host '번호를 고르세요 (1/2/N)'
    if ($choice -eq '1') {
        $port = Get-ServerPort
        Stop-IfPortTaken $port
        Write-Host ''
        Write-Host '폴더 권한을 확인하고 자동 시작을 다시 등록합니다.'
        $locked = Protect-PackageFolder
        if (-not $locked) {
            Write-Host '폴더를 잠그지 못해 자동 시작 등록을 하지 않습니다. 위 메시지를 확인해 주세요.'
            exit 1
        }
        $registered = Register-AutoStart
        $ipKeep = $null
        try {
            $ipKeep = [string](Get-Content -Raw -Encoding UTF8 -LiteralPath 'data\tls\server.json' | ConvertFrom-Json).hosts[0]
        } catch { }
        Write-Host ''
        if ($ipKeep) {
            Write-Host '서버를 켭니다.'
            Start-ServerAndCheck $ipKeep $port $registered
        } else {
            Write-Host '서버 주소를 읽지 못해 서버를 켜지 않았습니다. 서버시작.bat를 실행해 주세요.'
        }
        if ($registered) { Write-Host '끝났습니다.'; exit 0 }
        Write-Host '위 메시지를 확인해 주세요.'
        exit 1
    }
    if ($choice -ne '2') {
        Write-Host '설정을 바꾸지 않고 끝냅니다.'
        exit 0
    }
    $reset = $true
}

# 이 PC의 IP 고르기
Write-Host ''
Write-Host '이 PC의 IPv4 주소 목록입니다.'
$addrs = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*' })
for ($i = 0; $i -lt $addrs.Count; $i++) {
    Write-Host ('  {0}) {1}   [{2}]' -f ($i + 1), $addrs[$i].IPAddress, $addrs[$i].InterfaceAlias)
}
Write-Host ''
Write-Host '가상 어댑터(VMware, VirtualBox, WSL 등) 주소가 섞여 있을 수 있습니다.'
Write-Host '핸드폰이 접속할 병원 내부망 주소를 고르세요. 번호를 쓰거나 주소를 직접 입력할 수 있습니다.'
$ip = $null
for ($try = 0; $try -lt 100 -and -not $ip; $try++) {
    $ans = (Read-Host '번호 또는 IP 주소')
    if ($null -eq $ans) { break }
    $ans = $ans.Trim()
    if ($ans -match '^[0-9]+$' -and [long]$ans -ge 1 -and [long]$ans -le $addrs.Count) {
        $ip = $addrs[[int]$ans - 1].IPAddress
    } elseif ($ans -match '^(\d{1,3}\.){3}\d{1,3}$' -and [System.Net.IPAddress]::TryParse($ans, [ref]$null)) {
        $ip = $ans
    } else {
        Write-Host '올바른 번호나 IPv4 주소(예: 192.168.0.10)를 입력해 주세요.'
    }
}
if (-not $ip) {
    Write-Host 'IP 주소를 고르지 못했습니다. 처음설정.bat를 다시 실행해 주세요.'
    exit 1
}
Write-Host ''
Write-Host "선택한 주소: $ip"
Show-NetworkCategory $ip

# 다시 설정하는 경우, 실행 중인 서버가 새 인증서를 쓰도록 먼저 끈다.
if ($reset) {
    if ($script:DryRun) { Write-DryRun 'taskkill /IM milkbank-server.exe /F' }
    else { & taskkill /IM milkbank-server.exe /F *> $null }
}
# setup-tls 는 포트를 8443으로 정한다. 그 포트를 다른 서버가 쓰고 있으면 인증서를 만들기 전에 멈춘다.
Stop-IfPortTaken 8443

Write-Host ''
Write-Host '[1/4] 인증서와 서버 등록 QR을 만듭니다.'
& $script:Exe setup-tls --host $ip
if ($LASTEXITCODE -ne 0) {
    Write-Host ''
    Write-Host '인증서를 만들지 못했습니다. 위 메시지를 확인해 주세요.'
    exit 1
}

Write-Host ''
Write-Host '[2/4] 관리자 계정'
$demoFile = Join-Path $script:Root '데모계정.txt'
if (Test-Path -LiteralPath $demoFile) {
    Write-Host '이 폴더는 시험용 자료가 들어 있는 데모입니다. 관리자 계정을 새로 만들지 않고'
    Write-Host '데모계정.txt의 계정을 씁니다.'
    Write-Host ''
    Get-Content -Encoding UTF8 -LiteralPath $demoFile | ForEach-Object { Write-Host $_ }
    Write-Host ''
} else {
    $adminId = $null
    for ($try = 0; $try -lt 100 -and -not $adminId; $try++) {
        $ans = Read-Host '관리자 아이디를 입력하세요 (그냥 Enter를 누르면 admin)'
        if ($null -eq $ans) { break }
        $ans = $ans.Trim()
        if ($ans -eq '') { $ans = 'admin' }
        if ($ans -match '^[A-Za-z0-9_.-]+$') { $adminId = $ans }
        else { Write-Host '아이디에는 영문자, 숫자, 점, 밑줄, 하이픈만 쓸 수 있습니다.' }
    }
    if (-not $adminId) { $adminId = 'admin' }
    & $script:Exe create-admin --username $adminId --name 관리자
    if ($LASTEXITCODE -ne 0) {
        Write-Host ''
        Write-Host '관리자를 만들지 못했습니다. 이미 있는 아이디일 수 있습니다. 위 메시지를 확인해 주세요.'
    } else {
        Write-Host ''
        Write-Host '************************************************'
        Write-Host '**  위에 나온 임시 비밀번호를 지금 적어 두세요.  **'
        Write-Host '**  처음 로그인한 뒤 비밀번호를 바꿔야 합니다.  **'
        Write-Host '************************************************'
    }
}

Write-Host ''
Write-Host '[3/4] 폴더를 잠그고, PC가 켜지면 서버가 저절로 시작되도록 등록합니다.'
$registered = $false
$locked = Protect-PackageFolder
if (-not $locked) {
    Write-Host '폴더를 잠그지 못해 자동 시작 등록을 하지 않습니다. 위 메시지를 확인해 주세요.'
} else {
    $registered = Register-AutoStart
}

Write-Host ''
Write-Host '[4/4] 서버를 시작하고 확인합니다.'
$port = Get-ServerPort
Start-ServerAndCheck $ip $port $registered

Write-Host ''
Write-Host '============================================'
Write-Host ' 마지막으로 Windows 방화벽에서 직접 허용해 주세요'
Write-Host '============================================'
Write-Host ' 1. 시작 메뉴에서 "고급 보안이 포함된 Windows Defender 방화벽"을 엽니다.'
Write-Host " 2. 인바운드 규칙 → 새 규칙 → 포트 → TCP, 특정 로컬 포트 $port"
Write-Host ' 3. 연결 허용 → 이 PC가 실제로 쓰는 네트워크 종류를 체크합니다(도메인 또는 개인).'
Write-Host '    병원 내부망이 "도메인"이면 도메인에, "개인"이면 개인에 체크하세요. 공용은 체크하지 마세요. 이름 입력 후 마침.'
Write-Host ' 이 파일은 방화벽 규칙을 만들지 않습니다.'
Write-Host ''
Write-Host ' ※ "Windows 보안 경고" 창이 뜨면 개인 네트워크/도메인 네트워크를 체크하고 "액세스 허용"을 누르세요.'
Write-Host '    취소를 누르면 이 프로그램을 막는 규칙이 생겨 접속이 안 됩니다. (자동 시작 작업으로 서버를 켜면 이 창은 보통 뜨지 않습니다.)'
Write-Host ''
Write-Host '서버 등록 QR 이미지를 엽니다. 출력해서 신생아실에 보관하세요.'
$qr = Join-Path $script:Root '서버등록QR.png'
if ($script:DryRun) { Write-DryRun "start `"`" `"$qr`"" }
else { Start-Process -FilePath $qr }
exit 0
