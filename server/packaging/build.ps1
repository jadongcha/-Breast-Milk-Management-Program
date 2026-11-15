# 다른 PC용 서버 폴더를 만든다.
#   powershell -File packaging/build.ps1          -> dist/모유관리서버-데모/ (+ .zip)  (데모 자료 포함)
#   powershell -File packaging/build.ps1 -Real    -> dist/모유관리서버/ (+ .zip)       (빈 DB)
param([switch]$Real)

$ErrorActionPreference = 'Stop'
$server = Split-Path -Parent $PSScriptRoot
Set-Location $server

$name = if ($Real) { '모유관리서버' } else { '모유관리서버-데모' }
$target = Join-Path $server "dist\$name"
$zip = Join-Path $server "dist\$name.zip"

& uv run --with pyinstaller pyinstaller packaging/milkbank-server.spec --noconfirm
if ($LASTEXITCODE -ne 0) { throw 'PyInstaller 실패' }

if (Test-Path $target) { Remove-Item $target -Recurse -Force }
if (Test-Path $zip) { Remove-Item $zip -Force }
New-Item -ItemType Directory -Path $target | Out-Null
Copy-Item (Join-Path $server 'dist\milkbank-server\*') $target -Recurse

# 배치 파일·안내문(packaging/files)이 있으면 폴더 맨 위에 복사한다.
$files = Join-Path $PSScriptRoot 'files'
if (Test-Path $files) {
    Get-ChildItem $files -Force | Copy-Item -Destination $target -Recurse -Force
}

if (-not $Real) {
    New-Item -ItemType Directory -Path (Join-Path $target 'data') | Out-Null
    & uv run python scripts/make_demo_db.py (Join-Path $target 'data\milkbank.db') (Join-Path $target '데모계정.txt')
    if ($LASTEXITCODE -ne 0) { throw '데모 DB 만들기 실패' }
}

# 한글 파일 이름이 깨지지 않도록 UTF-8 이름으로 압축한다(폴더 이름 포함).
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($target, $zip, 'Optimal', $true, [System.Text.Encoding]::UTF8)

Write-Host "폴더: $target"
Write-Host "압축: $zip"
