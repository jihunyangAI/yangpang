# YangPang 데모 켜기: 앱 + Cloudflare 임시 터널 (테스트용)
# - 앱이 이미 떠 있으면 그대로 쓰고, 없으면 새 창("YangPang App")에서 띄운다
# - 터널 주소(https://....trycloudflare.com)를 보여 주고 클립보드에 복사한 뒤 브라우저로 연다
# - 끄기: stop-demo.bat
# 환경 변수 NO_OPEN=1 이면 브라우저를 열지 않고, NO_PAUSE=1 이면 마지막에 기다리지 않는다 (검사용)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Port = 8090
$Log = Join-Path $env:TEMP 'yangpang-tunnel.log'

function Test-App {
    try { return (Invoke-WebRequest "http://localhost:$Port/" -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200 } catch { return $false }
}
function Stop-WithMessage($msg) {
    Write-Host "[!] $msg" -ForegroundColor Red
    if (-not $env:NO_PAUSE) { Read-Host '엔터를 누르면 창을 닫아요' | Out-Null }
    exit 1
}

# cloudflared 찾기
$cf = (Get-Command cloudflared -ErrorAction SilentlyContinue).Source
if (-not $cf) {
    $cf = @("${env:ProgramFiles(x86)}\cloudflared\cloudflared.exe", "$env:ProgramFiles\cloudflared\cloudflared.exe") |
          Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $cf) { Stop-WithMessage 'cloudflared가 없어요. 설치: winget install --id Cloudflare.cloudflared -e' }

# Docker (PostgreSQL 컨테이너용)
& docker info *> $null
if ($LASTEXITCODE -ne 0) { Stop-WithMessage 'Docker Desktop이 꺼져 있어요. Docker Desktop을 켠 뒤 다시 실행하세요.' }

# 앱
if (Test-App) {
    Write-Host "[1/2] 앱이 이미 떠 있어요: http://localhost:$Port"
} else {
    Write-Host '[1/2] 앱을 새 창("YangPang App")에서 띄우는 중... 처음에는 1~2분 걸려요'
    # gradlew.bat는 전체 경로로 부른다 (현재 폴더 실행을 막는 설정이 켜진 환경에서도 동작하게)
    Start-Process cmd.exe -WorkingDirectory $Root -ArgumentList '/d', '/k', "title YangPang App && cd /d `"$Root`" && `"$Root\gradlew.bat`" :api:bootRun"
    $waited = 0
    while (-not (Test-App)) {
        Start-Sleep -Seconds 3
        $waited += 3
        if ($waited -ge 300) { Stop-WithMessage '5분이 지나도 앱이 뜨지 않았어요. "YangPang App" 창의 오류를 확인하세요.' }
    }
}
Write-Host '      앱 준비 완료'

# 터널 (별도 프로세스라 이 창을 닫아도 계속 돈다)
Write-Host '[2/2] 터널을 여는 중...'
Remove-Item $Log -ErrorAction SilentlyContinue
Start-Process $cf -WindowStyle Minimized -ArgumentList 'tunnel', '--no-autoupdate', '--url', "http://localhost:$Port", '--logfile', "`"$Log`""
$url = $null
for ($i = 0; $i -lt 60 -and -not $url; $i++) {
    Start-Sleep -Seconds 1
    if (Test-Path $Log) {
        $m = [regex]::Match((Get-Content -Raw $Log), 'https://[a-z0-9-]+\.trycloudflare\.com')
        if ($m.Success) { $url = $m.Value }
    }
}
if (-not $url) { Stop-WithMessage "터널 주소를 받지 못했어요. 로그: $Log" }

Set-Clipboard -Value $url
Write-Host ''
Write-Host ' ==========================================================' -ForegroundColor Cyan
Write-Host "  데모 주소:  $url" -ForegroundColor Yellow
Write-Host '  (클립보드에 복사했어요. 이 PC와 앱 창이 켜져 있는 동안만 열려요)'
Write-Host ' ==========================================================' -ForegroundColor Cyan
Write-Host ''
Write-Host '  끄려면 stop-demo.bat 을 실행하세요. (다시 켜면 주소가 바뀌어요)'
if (-not $env:NO_OPEN) { Start-Process $url }
if (-not $env:NO_PAUSE) { Read-Host '엔터를 누르면 이 안내 창만 닫혀요 (데모는 계속 켜져 있음)' | Out-Null }
