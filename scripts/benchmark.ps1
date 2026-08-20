# 언어별 토큰 벤치마크 실행 스크립트
#
#   .\scripts\benchmark.ps1
#
# 측정 결과를 터미널에 그대로 출력한다. 화면 자체를 증빙 자료(스크린샷)로 쓰기 위한 것이라
# Gradle이 붙이는 태스크 로그와 들여쓰기를 걷어낸다.
#
# 산출물
#   build/measurement/benchmark-raw-http-log.md    DeepL 요청·응답 원본
#   build/measurement/benchmark-language-tokens.md 요약 + 검증 방법
#   docs/benchmark-language-tokens.html            차트
#
# DeepL 문자 할당량을 소모한다. OpenAI 호출은 없다.

$ErrorActionPreference = 'Stop'

Set-Location (Join-Path $PSScriptRoot '..')

# JAVA_HOME이 시스템에 설정돼 있지 않은 환경이 있어 후보 경로를 훑는다.
if (-not $env:JAVA_HOME) {
    $candidates = @(
        'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.1\jbr'
    ) + (Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue |
         Sort-Object Name -Descending | Select-Object -ExpandProperty FullName)

    foreach ($c in $candidates) {
        if (Test-Path (Join-Path $c 'bin\java.exe')) { $env:JAVA_HOME = $c; break }
    }
}

if (-not $env:JAVA_HOME) {
    Write-Host 'JAVA_HOME을 찾지 못했습니다. JDK 21 경로를 직접 지정하세요.' -ForegroundColor Red
    Write-Host "예: `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'" -ForegroundColor DarkGray
    exit 1
}

Write-Host ''
Write-Host "JAVA_HOME  $env:JAVA_HOME" -ForegroundColor DarkGray
Write-Host ''

# Gradle 태스크 로그와 4칸 들여쓰기를 제거해 측정 출력만 남긴다.
& .\gradlew.bat benchmark --console=plain 2>&1 |
    Where-Object {
        $_ -notmatch '^> (Task|Configure)' -and
        $_ -notmatch 'STANDARD_OUT' -and
        $_ -notmatch '^(BUILD|FAILURE)' -and
        $_ -notmatch 'actionable task' -and
        $_ -notmatch 'configuration cache' -and
        $_ -notmatch '^\.env 로드'
    } |
    ForEach-Object { $_ -replace '^    ', '' }
