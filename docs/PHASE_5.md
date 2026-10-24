# Phase 5A — Discord Incoming Webhook 모듈

Phase 5A만 구현했다. Discord 발송 모듈과 Mock 테스트 범위이며, 기존 수집/이메일 작업의 다중 채널 배분은 Phase 5C 후속 작업이다.
실제 Discord·OpenDART·Gmail을 호출하지 않았다. 신규 라이브러리·REST API·Entity·Flyway 마이그레이션은 없다.

## 구조와 기존 코드 재사용

- `discord/DiscordProperties`: enabled/URL/연결·응답 timeout. 기본 false이며 잘못된 URL 때문에 앱 기동을 막지 않는다.
- `DiscordConfiguration`: 전용 Spring RestClient와 JDK HttpClient. 연결 5초·응답 10초, redirect를 따라가지 않는다.
- `DiscordWebhookClient`: 검증된 설정 URL로 POST 1회, HTTP/본문 처리·안전한 결과·429 not-before 관리.
- `DiscordMessage`: 기존 NotificationSender.Message를 한 개 Embed로 변환한다.
- `DiscordNotificationSender`: 비활성화 검사, 메시지 구성, 클라이언트 호출. 채널 식별은 `channel() = DISCORD`다.

NotificationSender의 기존 send(Message)와 3인자 SendResult 생성자/팩토리를 유지했다.
선택 필드 externalMessageId와 retryAfter(Duration)를 추가했고 기존 이메일 결과는 모두 null이다.
channel()의 기본값은 EMAIL이며 EmailNotificationSender를 @Primary로 지정했다.
기존 NotificationDispatchService는 계속 이메일을 사용하며 Discord가 기본 발송자를 대체하지 않는다.

DB NotificationChannel/제약조건·이메일 수신자 정규화·알림 조건/활성 기준·선점·재시도 워커는 변경하지 않았다.
DISCORD는 이 모듈의 논리적 식별자이며 이번에 DB 채널로 등록하지 않는다. Message.recipient는 Discord에서 사용하지 않으며
Webhook URL을 이 필드나 발송 결과에 넣지 않는다. 대상은 환경설정 URL 하나다.

## 공식 API와 메시지

[Execute Webhook 공식 문서](https://docs.discord.com/developers/resources/webhook#execute-webhook)에 따라 wait=true로 요청한다.
HTTP 200과 숫자 문자열 메시지 ID를 함께 확인할 때만 SENT를 반환한다. 메시지 ID는 결과로 반환하지만 이번에 DB 저장하지 않는다.
204·빈/잘못된 응답·메시지 ID 누락은 성공으로 단정하지 않는다. wait=false 요청은 제공하지 않는다.

Embed에는 고정 제목, 기업명·보고서명·공식 유형 A~E·접수일·접수번호로 만든 DART 링크를 넣는다.
외부 입력으로 URL/제목/멘션 정책을 덮어쓸 수 없다. 단일 Embed, 기업명 최대 256 UTF-16 단위, 보고서명 1024 단위이며
합계 텍스트는 1600자 미만이다. 잘라낼 때 surrogate pair를 분리하지 않는다.
`allowed_mentions: {parse: []}`로 모든 멘션 해석을 끄고 외부 텍스트의 @ 뒤에 zero-width space도 넣는다.
제어문자를 제거하며 입력 누락·잘못된 접수번호/유형은 HTTP 요청 없이 COMPOSITION_FAILED다.

기준: [Embed 제한](https://docs.discord.com/developers/resources/message#embed-object),
[Allowed Mentions](https://docs.discord.com/developers/resources/message#allowed-mentions-object).
현재는 Embed 하나이며 첨부파일·thread/forum·복잡한 템플릿은 지원하지 않는다.

## 오류·불확실성·재시도 책임

| 상황 | 결과 / 정책 |
| --- | --- |
| 비활성 | FAILED / DISCORD_DISABLED, HTTP 0회 |
| URL 누락·잘못된 설정 | FAILED / CONFIGURATION, HTTP 0회 |
| HTTP 200 + 정상 id | SENT, externalMessageId 반환 |
| 400 | 영구 FAILED / DISCORD_BAD_REQUEST |
| 401·403 | 영구 FAILED / DISCORD_AUTHENTICATION |
| 404 | 영구 FAILED / DISCORD_WEBHOOK_NOT_FOUND |
| 기타 4xx(429 제외) | 영구 FAILED / DISCORD_REJECTED |
| 429 | FAILED / DISCORD_RATE_LIMIT, retryable=true, retryAfter 반환 및 호출 차단 |
| 5xx | UNKNOWN / DISCORD_SERVER_UNCERTAIN. POST 저장 후 오류일 수 있어 자동 재전송 금지 |
| 연결 거부/DNS/명확한 connect timeout | FAILED / CONNECTION_FAILED, 호출자가 제한 재시도할 수 있음 |
| 읽기/일반 timeout | UNKNOWN / DISCORD_TIMEOUT_UNCERTAIN |
| 일반 전송 오류·redirect·204·잘못된 성공 본문 | UNKNOWN / TRANSPORT_UNCERTAIN |

클라이언트와 Sender 모두 내부 재전송은 **0회**, send 한 번당 HTTP 최대 1회다. 대기 sleep/백오프 루프도 없다.
기존 이메일 워커와 별도 retry 계층을 중첩하지 않는다. retryable은 재시도가 안전한 분류이지 이번 단계의 자동 실행 약속이 아니다.
현재 Discord용 작업 생성/재시도 스케줄러는 없다. Phase 5C에서 기존 워커의 시도 상한을 재사용해야 한다.
5xx는 일시적인 원인일 수 있지만 메시지 미저장을 확신할 수 없어 보수적으로 UNKNOWN으로 격리한다.

429는 [공식 Rate Limits](https://docs.discord.com/developers/topics/rate-limits)에 따라 Retry-After 또는 JSON retry_after 초 값을 사용한다.
소수 초는 밀리초로 올림하며, 둘 다 유효하면 더 긴 값, HTTP 날짜 형식도 지원한다. 정보가 없거나 손상되면 60초를 적용한다.
그 기간의 추가 호출은 외부 HTTP 없이 남은 retryAfter를 반환하고 차단을 연장하지 않는다. 만료 후 명시적 새 호출만 허용한다.
메모리 상태라 재시작하면 초기화된다. 호출은 클라이언트 단위로 직렬화하며 분산 rate limiter는 추가하지 않았다.

SENT는 Discord 생성 응답 확인이지 사용자 열람 확인이 아니다. 외부 HTTP와 DB를 원자적으로 묶거나 exactly-once를 보장하지 않는다.

## 환경변수와 URL 보호

| 변수 | 기본값 / 규칙 |
| --- | --- |
| DISCORD_ENABLED | false. 기존 COLLECTION_ENABLED/NOTIFICATION_ENABLED와 별도 |
| DISCORD_WEBHOOK_URL | 빈 값. 실행 환경에서만 실제 값 공급 |
| DISCORD_CONNECT_TIMEOUT | PT5S, 1ms~1분 |
| DISCORD_READ_TIMEOUT | PT10S, 1ms~1분 |

수집이나 이메일 API는 Discord를 호출하지 않는다. DISCORD_ENABLED=true만 설정해도 자동 발송되지 않는다.
URL은 https + discord.com/discordapp.com, 기본 포트/443, `/api/[v10/]webhooks/{숫자id}/{token}`만 허용한다.
사용자 정보·query·fragment·다른 호스트/포트·경로 인코딩은 거부하고 wait=true는 클라이언트가 붙인다.
Redirect는 금지한다. 토큰이 URL 경로에 있으므로 원본 URI·본문·예외·설정 객체를 로그/반환 결과에 기록하지 않는다.
인증 헤더·Bot Token·OAuth는 사용하지 않는다. 테스트 URL은 명시적으로 가짜이며 Mock HTTP로 차단된다.
기존 RestClient 로그 비활성화를 유지했다. 실제 URL을 소스/테스트/문서/명령 히스토리에 복사하지 않는다.

기존 .env.example의 Kakao placeholder는 사용자 변경으로 유지했으며 Kakao 기능은 추가하지 않았다.
모든 Gradle 테스트는 dartwatch.discord.enabled=false를 강제하고, 단위 테스트의 독립 설정에서만 Mock HTTP에 대해 활성화한다.

## 테스트

DiscordWebhookTests는 요청/Embed/메시지 ID·크기/Unicode·allowed_mentions·오류·429 대기/억제·5xx/timeout/불확실성·URL 검증·비활성화를 검증한다.
DiscordWiringTests는 Discord를 켜도 기본 발송자가 EMAIL이고, 기본 설정에서는 HTTP 호출이 없음을 확인한다.
기존 EmailNotificationSender/로컬 SMTP/발송 이력·DB 선점·UNKNOWN·수집 E2E도 회귀 실행한다.
신규 라이브러리는 없으며 기존 MockRestServiceServer·JUnit 5·Mockito를 사용한다.

실행 결과 (2026-10-24): `build integrationTest` 성공.

| 구분 | 통과 | 실패 | 오류 | 건너뜀 |
| --- | ---: | ---: | ---: | ---: |
| 기본/단위 | 189 | 0 | 0 | 0 |
| PostgreSQL 통합 | 93 | 0 | 0 | 0 |
| 합계 | 282 | 0 | 0 | 0 |

기존 237개와 신규 Discord Mock HTTP 44개·발송자 wiring 1개를 포함한다.
이메일 sender·로컬 SMTP·DB 작업/중복/복구·수집 회귀 테스트가 통과했다.
실제 Discord·OpenDART·Gmail 및 외부 opt-in 테스트는 실행하지 않았다. 새 DB/Entity/마이그레이션·의존성은 없다.
실제 Webhook의 생성/Discord 채널 표시·사용자 알림·운영 중 rate limit·재시작 후 Discord 복구는 NOT VERIFIED다.

## 사람이 직접 테스트 메시지 1건을 보내는 방법 — 이번에는 실행하지 않음

서버에서 Webhook을 만들 수 있는 권한이 있는 사용자가 Discord 채널의 Integrations/Webhooks 설정에서 전용 테스트 Webhook을 준비한다.
실제 URL은 OS 환경변수 DISCORD_WEBHOOK_URL에 안전하게 공급하고 화면/채팅/저장소에 공유하지 않는다.
앱의 수집·이메일·Discord 설정은 false로 유지하거나 앱을 중지한다. **실제 발송을 승인한 사람이 직접** 아래 PowerShell 7 코드를 한 번 실행한다.
이 코드는 Discord 연결만 검사하며 앱의 작업 생성/DB 이력/5C 통합 검증은 아니다. 모듈의 Embed는 위 Mock 테스트로 검증한다.

```powershell
try {
    $target = [uri]$env:DISCORD_WEBHOOK_URL
    if ($target.Scheme -ne 'https' -or $target.Host -ne 'discord.com' -or
        $target.Port -ne 443 -or
        $target.Query -or $target.Fragment -or $target.UserInfo -or
        $target.AbsolutePath -notmatch '^/api/(v10/)?webhooks/[1-9][0-9]{0,19}/[A-Za-z0-9_-]{1,200}$') {
        throw 'Invalid configuration'
    }
    $payload = @{ content = 'DartWatch 수동 Webhook 연결 확인 (1회)'; allowed_mentions = @{parse=@()} } | ConvertTo-Json -Depth 4
    $reply = Invoke-RestMethod -Method Post -Uri ($target.AbsoluteUri + '?wait=true') `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($payload)) `
        -TimeoutSec 10 -MaximumRetryCount 0 -MaximumRedirection 0 -Verbose:$false -Debug:$false -ErrorAction Stop
    if ($reply.id -match '^[1-9][0-9]{0,19}$') { Write-Output ('생성된 Discord 메시지 ID: ' + $reply.id) }
    else { Write-Warning '발송 결과 미확인. 자동으로 재실행하지 마세요.' }
} catch {
    Write-Warning '설정 오류·거절 또는 결과 불확실. 원본 예외/URL을 공유하거나 자동 재실행하지 마세요.'
}
```

timeout/5xx/결과 미확인이면 채널을 먼저 확인하고 중복 위험이 있는 재발송을 하지 않는다.
테스트 후 필요하면 Discord에서 테스트 Webhook을 삭제/재생성해 토큰을 폐기한다. 이번 작업에서는 이 코드를 실행하지 않았다.

## Phase 5C 후속 작업

- DB NotificationChannel과 CHECK·수신 대상 모델을 새 마이그레이션으로 확장. 기존 이메일 주소 정규화 규칙을 Webhook에 적용하지 않기.
- 채널/대상별 조건·활성 기준·중복키·선점·이력과 Sender 라우팅. URL 대신 비밀정보를 포함하지 않는 대상 식별자 사용.
- externalMessageId와 retryAfter/not-before 영속화. 기존 재시도 상한과 provider 대기를 함께 지키고 HTTP 내부 retry와 중첩하지 않기.
- DISCORD_* 실패 코드 이력 조회 정책, UNKNOWN 격리와 재시작 rate-limit 복구, 여러 Webhook의 bucket/global 제한 검토.
- Discord 메시지 ID가 있더라도 사용자 열람/Exactly-once를 주장하지 않기.

Phase 5B·5C, 실제 Discord 발송, Commit·Push·배포는 수행하지 않았다.
