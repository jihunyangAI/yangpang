# YangPang 데모 끄기: 터널과 YangPang 앱만 끈다
# 다른 Java 프로그램(예: 다른 프로젝트 서버)은 건드리지 않는다: 명령줄에 이 프로젝트 경로가 있는 java만, Gradle 데몬 제외
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Write-Host '터널 끄는 중...'
Get-Process cloudflared -ErrorAction SilentlyContinue | Stop-Process -Force
Write-Host 'YangPang 앱 끄는 중...'
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like "*$Root*" -and $_.CommandLine -notmatch 'GradleDaemon' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host "  종료: $($_.ProcessId)" }
Get-CimInstance Win32_Process -Filter "Name='cmd.exe'" |
    Where-Object { $_.CommandLine -match 'gradlew.bat :api:bootRun' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Write-Host '완료. (PostgreSQL 컨테이너는 그대로 둠: 끄려면 docker compose down)'
if (-not $env:NO_PAUSE) { Read-Host '엔터를 누르면 창을 닫아요' | Out-Null }
