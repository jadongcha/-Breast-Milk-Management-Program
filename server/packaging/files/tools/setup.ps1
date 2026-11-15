# 처음설정.bat 가 부른다. 인증서, 관리자 계정, 자동 시작 등록, 서버 시작까지 한 번에 한다.
. (Join-Path $PSScriptRoot 'common.ps1')

if (-not $script:DryRun -and -not (Test-Admin)) {
    Write-Host '이 파일을 마우스 오른쪽 → 관리자 권한으로 실행해 주세요.'
    exit 1
}

Write-Host '============================================'
Write-Host '  모유 관리 서버 처음 설정'
Write-Host '============================================'
Write-Host ''

# 이미 설정되어 있으면 무엇을 할지 묻는다
$reset = $false
if (Test-Path -LiteralPath 'data\tls\server.json') {
    Write-Host '이미 설정되어 있습니다.'
    Write-Host '  [1] 자동 시작만 다시 등록 (인증서는 그대로, 기기는 QR을 다시 찍지 않아도 됩니다)'
    Write-Host '  [2] 처음부터 다시 설정 (인증서를 새로 만들어서 모든 기기에서 QR을 다시 찍어야 합니다)'
    Write-Host '  [N] 취소'
    $choice = Read-Host '번호를 고르세요 (1/2/N)'
    if ($choice -eq '1') {
        Write-Host ''
        Write-Host '폴더 권한을 확인하고 자동 시작을 다시 등록합니다.'
        $locked = Protect-PackageFolder
        if (-not $locked) {
            Write-Host '폴더를 잠그지 못해 자동 시작 등록을 하지 않습니다. 위 메시지를 확인해 주세요.'
            exit 1
        }
        if (Register-AutoStart) { Write-Host '끝났습니다.'; exit 0 }
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

# 다시 설정하는 경우, 실행 중인 서버가 새 인증서를 쓰도록 먼저 끈다.
if ($reset) {
    if ($script:DryRun) { Write-DryRun 'taskkill /IM milkbank-server.exe /F' }
    else { & taskkill /IM milkbank-server.exe /F *> $null }
}

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
$locked = Protect-PackageFolder
if (-not $locked) {
    Write-Host '폴더를 잠그지 못해 자동 시작 등록을 하지 않습니다. 위 메시지를 확인해 주세요.'
} else {
    [void](Register-AutoStart)
}
$bat = Join-Path $script:Root '서버시작.bat'

Write-Host ''
Write-Host '[4/4] 서버를 시작하고 확인합니다.'
$port = Get-ServerPort
if ($script:DryRun) {
    Write-DryRun '서버시작.bat /background 를 숨긴 창으로 실행'
} else {
    Start-Process -FilePath $bat -ArgumentList '/background' -WindowStyle Hidden
    # 자체 서명 인증서라서 인증서 검사를 끄고(-k) 확인한다. PowerShell 5.1의 웹 요청은 이 검사를 끄기 어려워 curl.exe를 쓴다.
    $status = 'FAIL'
    for ($n = 0; $n -lt 6 -and $status -ne '200'; $n++) {
        Start-Sleep -Seconds 3
        $code = (& curl.exe -k -s -o NUL -w '%{http_code}' --max-time 5 "https://${ip}:${port}/health" 2>$null)
        if ($code -eq '200') { $status = '200' } else { $status = 'FAIL' }
    }
    if ($status -eq '200') {
        Write-Host "서버가 잘 켜졌습니다. (https://${ip}:${port}/health 확인 결과: 200)"
    } else {
        Write-Host "서버가 아직 응답하지 않습니다. (확인 결과: $status)"
        Write-Host 'data\logs\server.log 를 확인하거나 서버시작.bat를 직접 실행해 보세요.'
    }
}

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
Write-Host '서버 등록 QR 이미지를 엽니다. 출력해서 신생아실에 보관하세요.'
$qr = Join-Path $script:Root '서버등록QR.png'
if ($script:DryRun) { Write-DryRun "start `"`" `"$qr`"" }
else { Start-Process -FilePath $qr }
exit 0
