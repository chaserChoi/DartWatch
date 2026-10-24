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

---

## Phase 5B — Kakao OAuth와 나에게 보내기

### 구현 범위와 공식 문서

단일 사용자 본인 계정 연결·토큰 보관/갱신·독립 KakaoNotificationSender를 추가했다.
기존 이메일의 @Primary 발송자, 공시 필터/활성 기준, 이메일 작업·DB 선점·UNKNOWN 정책과 Discord 모듈은 유지했다.
기존 AGENTS의 이메일 중심 MVP 원칙 이후, 이번 명시적 Phase 5B 요청에 따라 Kakao 모듈만 확장했다.
채널별 조건·작업 배분·이력 통합은 구현하지 않았다. 신규 라이브러리·Spring Security·프론트엔드·외부 인프라는 없다.

공식 근거:

- [카카오 로그인 REST API](https://developers.kakao.com/docs/ko/kakaologin/rest-api): 인가 코드·토큰 발급/갱신·리프레시 토큰 교체 조건.
- [카카오톡 메시지 REST API](https://developers.kakao.com/docs/ko/kakaotalk-message/rest-api): 나에게 보내기, form template_object, result_code.
- [기본 메시지 템플릿](https://developers.kakao.com/docs/ko/message-template/default): text 200자 및 제품 링크의 등록 도메인 정책.
- [REST API 오류 코드](https://developers.kakao.com/docs/ko/rest-api/error-code), [쿼터](https://developers.kakao.com/docs/ko/getting-started/quota).

### 코드와 데이터 구조

`kakao/` 패키지에 다음 책임을 분리했다.

| 파일 | 역할 |
| --- | --- |
| KakaoProperties / KakaoConfiguration | 환경변수·전용 RestClient·5초 연결/10초 응답 timeout, HTTP redirect 금지 |
| KakaoClient / KakaoFailure | 고정된 공식 HTTPS endpoint, form 요청, 상태/본문 검증, 안전한 오류·cooldown |
| KakaoOAuthService | 브라우저 세션에 연결된 state와 인가 URL·콜백 검증 |
| KakaoCrypto / KakaoTokenStore / KakaoTokenService | AES-GCM, JDBC 짧은 트랜잭션, 단일 계정 연결/갱신/복구 |
| KakaoMessage / KakaoNotificationSender | 200자 텍스트, 링크 정책, 공통 SendResult 및 KAKAO 식별 |
| KakaoLocalController / KakaoLocalGuard / KakaoLocalConfiguration | 기본 미등록인 로컬 관리 API와 접근 제한 |

V5 `kakao_connection` 테이블을 추가했다. singleton_id=1만 허용하며 토큰 암호문·두 만료 시각·상태·갱신 시각·안전한 오류 코드를 저장한다.
앱 키 SHA-256 fingerprint를 저장하고 암호화 AAD에도 앱/토큰 용도를 묶는다. 실제 앱 키는 DB에 저장하지 않는다.
CONNECTED/REFRESHING 상태의 토큰은 NULL 또는 평문으로 저장할 수 없고 RECONNECT_REQUIRED는 토큰을 제거한다.
V1~V4·기존 Entity·알림 채널 EMAIL 제약조건은 변경하지 않았다. V5도 적용 후 수정하지 않는다.

### OAuth와 로컬 전용 관리

1. 로컬 연결 화면에서 사용자가 POST로 시작한다. scope=talk_message, response_type=code를 사용한다.
2. SecureRandom 32바이트 state를 만들고 세션에는 SHA-256 hash·5분 만료만 저장한다.
3. 콜백은 동일 세션, state 상수 시간 비교, 만료를 검증하고 교환 전에 원자적으로 소비한다.
   새 연결 시작은 이전 state를 대체하며 실패/취소/인가 코드 교환 실패 후에도 재사용할 수 없다.
4. kauth 토큰 endpoint에 grant_type/client_id/redirect_uri/code와 설정된 client_secret을 form으로 보낸다.
5. 콜백 성공/취소는 코드가 없는 상태 URL로 303 이동한다. 상태 API는 연결 상태·만료 시각·안전한 오류만 반환한다.

관리 API는 `KAKAO_LOCAL_CONNECT_ENABLED=true`일 때만 등록하고 Swagger에는 노출하지 않는다.
명시적 SERVER_ADDRESS=127.0.0.1 또는 ::1 바인딩과 로컬 http callback 경로를 요구하며, 잘못된 설정은 기동을 거부한다.
remote/local IP·Host가 loopback/등록 redirect와 일치해야 하고, proxy forwarding 헤더와 forwarding 설정은 거부한다.
POST는 같은 Origin이 필수다. 쿠키는 HttpOnly·SameSite=Lax이며 URL 세션 추적은 사용하지 않는다.
응답은 no-store/no-referrer/CSP/frame 차단, 기본 access log 비활성화·Spring Web 상세 로그 억제를 적용했다.
인가 코드가 브라우저의 callback URL에 도착하는 OAuth 특성은 남으므로 공유 PC·확장 프로그램·브라우저 기록도 주의한다.
로그 레벨/접근 로그를 켜거나 리버스 프록시·터널·공개 바인딩으로 이 경로를 공개하지 않는다.
관리 경로에 대한 별도 인증이 있는 공개 운영 서비스로 사용할 수 없다. 종료 시 로컬 관리 설정을 false로 되돌린다.

| 메서드 | 로컬 전용 경로 | 동작 |
| --- | --- | --- |
| GET | /api/integrations/kakao/connect | 본인 계정 연결 시작 화면, 전송 없음 |
| POST | /api/integrations/kakao/authorize | confirmConnect=true·동일 Origin 필수, 인가 페이지로 이동 |
| GET | /api/integrations/kakao/callback | 세션/state 검증·코드 교환, 전송 없음 |
| GET | /api/integrations/kakao/status | 비밀값 없는 연결 상태 |
| POST | /api/integrations/kakao/test-message | confirmSend=true·동일 Origin 필수, **실제 1건 전송을 일으킬 수 있음** |

인가 실패/취소는 안전한 상태만 반환하고 공급자의 error_description·원본 예외·인가 코드·토큰은 응답/로그에 넣지 않는다.

### 토큰 보관·갱신·재시작

환경변수의 Base64 32바이트 키로 AES-256-GCM 암호화한다. 매 암호화마다 무작위 12바이트 nonce와 인증 태그를 사용한다.
키는 DB와 분리 보관·백업하고 로그/채팅/저장소에 공개하지 않는다. 키 유실·교체 시 기존 토큰은 해독할 수 없으며 재연결이 필요하다.
암호화는 DB 유출 범위를 줄이지만 앱과 키가 함께 유출되는 경우의 보호를 보장하지 않는다.

액세스 만료 30초 전부터 갱신한다. synchronized 서비스로 단일 인스턴스에서 갱신을 직렬화하고,
이미 다른 요청이 갱신한 경우 새 토큰을 재사용한다. 새 refresh_token이 없으면 기존 토큰과 원래 만료 시각을 보존하고,
있으면 새 토큰/만료로 교체한다. 토큰 응답 저장이 완료된 뒤에만 발송자가 새 액세스 토큰을 사용한다.

HTTP 호출 전 짧은 DB 트랜잭션으로 REFRESHING 의도를 먼저 저장한다. HTTP 호출 동안 DB 트랜잭션을 잡지 않는다.
회전 중단·응답/DB 저장 유실·재시작 뒤 REFRESHING은 이전 토큰으로 자동 재시도하지 않고 RECONNECT_REQUIRED로 격리한다.
명확한 한도/설정 거절이면 기존 토큰을 보존해 CONNECTED로 복원한다. refresh 만료·invalid_grant·앱 변경은 재연결이 필요하다.
정상 CONNECTED는 재시작 후 DB에서 복원한다. OAuth 진행 중 세션은 재시작 후 복원하지 않으며 연결부터 다시 시작한다.
다중 인스턴스 토큰 소유권·암호화 키 교체 도구는 범위 밖이다.

### 메시지와 링크 정책

text 기본 템플릿의 object_type/text/link/button_title을 사용한다. 기업명·보고서·A~E 유형·접수일·접수번호·DART 주소를 포함한다.
긴 기업명·보고서명만 안전하게 줄여 전체를 200 UTF-16 단위 이내로 제한하고 나머지 필드는 유지한다.
한국어 form encoding을 Mock으로 확인한다. URL은 rcept_no 14자리로 생성하며 외부 입력 URL을 전송하지 않는다.

**현재 등록 웹 도메인은 사용자가 확인하지 못한 상태다.** KAKAO_REGISTERED_WEB_ORIGIN과 KAKAO_MESSAGE_WEB_URL은 기본 빈 값이고,
유효한 제품 링크가 없으면 토큰 조회/갱신·메시지 HTTP 호출 전에 KAKAO_LINK_CONFIGURATION으로 실패한다.
본문의 DART 주소가 클릭되는지는 클라이언트 동작에 따라 달라 보장하지 않는다.

- 실제 등록된 서비스 origin과 동일 scheme/host/port의 message URL을 지정하면 버튼은 `등록 서비스 열기`다.
  본문 DART 주소·접수번호를 사용자가 확인하는 대안이며 우회 리다이렉트 서버는 만들지 않았다.
- 카카오 콘솔/정책에서 `https://dart.fss.or.kr` 제품 링크가 실제로 허용된 것을 확인한 경우에만
  그 origin을 명시하면 `DART 원문` 버튼을 구성한다. 문자열 설정만으로 카카오 등록을 검증하거나 허용을 보장하지 않는다.
- 등록할 수 있는 도메인/유효 링크가 없다면 발송 기능은 사용할 수 없다. **실제 클릭 가능한 원문 버튼은 NOT VERIFIED다.**

### 오류·재시도·불확실성

| 상황 | 처리 |
| --- | --- |
| HTTP 200 + 정수 result_code=0 | SENT: 카카오 API 정상 처리 응답, 사용자 열람 보장 아님 |
| 명확한 401/-401 | 토큰 갱신 후 메시지 1회만 다시 요청. 재거절이면 재연결 |
| refresh invalid_grant/만료 | 토큰 제거·RECONNECT_REQUIRED, 자동 반복 없음 |
| -402/-3/-5, 403 | 동의/권한 확인 및 재연결 필요, 재시도 금지 |
| 명확한 템플릿 4xx 거절 | FAILED, 재시도 금지 |
| 429, 메시지 -10, 토큰 KOE237 | FAILED + retryAfter, 해당 endpoint 추가 호출 억제 |
| 메시지 5xx/timeout/잘못된 성공 응답/-603 등 | UNKNOWN, 자동 재발송 금지 |
| 발송 전 토큰 장애 | 확정 미발송 FAILED. 불확실한 refresh 회전은 재연결 필요 |
| 비활성화/링크 미설정 | 호출 전 FAILED |

Retry-After의 양수 초를 사용하고, 명확한 해제 시각이 없으면 보수적으로 24시간 대기한다.
이는 카카오의 실제 쿼터 리셋 시각을 단정하는 값이 아니다. 앱/사용자·API별 공식 쿼터는 콘솔에서 확인해야 한다.
cooldown은 메모리 상태여서 재시작 시 사라진다. 제한을 우회하려고 반복 재시작하지 않는다.
클라이언트의 HTTP 자동 재시도·sleep·무한 루프는 없다. 제한적 재시도/영속 not-before는 5C 작업 배분에서 연결한다.
UNKNOWN을 FAILED로 바꾸어 무조건 재전송하지 않는다. 현재 수동 테스트 호출은 DB 발송 이력/멱등키를 만들지 않으므로
사용자가 직접 반복 호출하면 중복 메시지가 생길 수 있다. 자동화에 연결하지 않는다. exactly-once를 보장하지 않는다.

### 환경변수와 사람이 직접 연결/1건 확인하는 절차 — 이번에는 실행하지 않음

| 변수 | 의미 / 기본값 |
| --- | --- |
| KAKAO_ENABLED | 독립 Kakao 모듈 사용, false |
| KAKAO_LOCAL_CONNECT_ENABLED | 로컬 관리 API 등록, false |
| KAKAO_REST_API_KEY / KAKAO_CLIENT_SECRET | 앱 REST 키 / 활성화된 경우 secret, 기본 빈 값 |
| KAKAO_REDIRECT_URI | http://localhost:8080/api/integrations/kakao/callback, 콘솔 값과 정확히 일치 |
| KAKAO_TOKEN_ENCRYPTION_KEY | Base64 32바이트 별도 암호화 키, 빈 값 |
| KAKAO_REGISTERED_WEB_ORIGIN | 실제 카카오 제품 링크 등록 origin, 빈 값 |
| KAKAO_MESSAGE_WEB_URL | 같은 origin의 실제 서비스 URL, 빈 값 |
| KAKAO_CONNECT_TIMEOUT / KAKAO_READ_TIMEOUT | PT5S / PT10S, 1ms~1min |
| SERVER_ADDRESS | 로컬 관리 시 127.0.0.1 또는 ::1 명시 필수 |

1. 사람이 콘솔에서 로그인 활성화·Redirect URI·talk_message 동의·시크릿 사용 여부와 제품 링크 등록 도메인을 확인한다.
   인가 코드/토큰은 수동 복사하지 않는다. 실제 값은 OS 비밀 환경변수 또는 Git 제외 .env에 직접 입력한다.
2. 암호화 키는 사용자가 암호학적 난수 32바이트를 Base64로 생성해 안전한 저장소에 보관하고 환경변수로 공급한다.
   PowerShell 7에서는 `$env:KAKAO_TOKEN_ENCRYPTION_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`를
   개인 환경에서 사용할 수 있다. 키를 출력/공유하거나 셸 기록·로그·저장소에 남기지 않도록 관리한다.
3. COLLECTION_ENABLED/NOTIFICATION_ENABLED/NOTIFICATION_SCHEDULER_ENABLED/DISCORD_ENABLED=false를 유지하고,
   SERVER_ADDRESS=127.0.0.1, KAKAO_ENABLED=true, KAKAO_LOCAL_CONNECT_ENABLED=true로 앱을 시작한다.
   DB를 먼저 실행한다. localhost 포트와 redirect를 일치시킨다. 공개 프록시/터널은 사용하지 않는다.
4. 브라우저에서 `http://localhost:8080/api/integrations/kakao/connect`를 열어 본인 계정 연결을 시작하고 동의한다.
   상태 URL의 CONNECTED·만료 시각만 확인한다. 이 단계는 메시지를 보내지 않는다.
5. **실제 1건 발송을 승인한 사람만**, 등록 링크를 먼저 설정한 뒤 같은 localhost 브라우저 탭의 개발자 콘솔에서 아래 코드를 1회 실행한다.
   이 코드는 실제 전송할 수 있으며 테스트용 수신함/본인 계정인지 확인한다. 일반 자동 테스트에서는 실행하지 않는다.

```javascript
const response = await fetch('/api/integrations/kakao/test-message', {
  method: 'POST', headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({confirmSend: true, companyName: 'DartWatch 테스트',
    reportName: '수동 연결 확인 (1회)', typeCode: 'A', receiptDate: '2026-10-24',
    rceptNo: '20261024000001'})
});
console.log(await response.json());
```

예시 접수번호는 검증용 가상 값이다. 원문까지 확인하려면 공개된 실제 공시의 접수번호/접수일을 사람이 선택한다.
SENT만 정상 API 응답으로 보고 카카오톡에서 표시를 확인한다. UNKNOWN이면 확인 전 재실행하지 않는다.
연결/전송 확인 후 로컬 관리와 KAKAO_ENABLED를 false로 되돌리고 앱을 재시작한다.
공유 기기에서 브라우저 OAuth 기록/세션을 정리하며, 필요 시 카카오 계정에서 연결을 해제한다.

### 검증과 Phase 5C 후속 작업

검증 결과는 아래 최종 실행 기록에 기재한다. 모든 카카오 HTTP는 Mock이며 실제 로그인·카카오톡·Discord·OpenDART·Gmail 호출은 하지 않았다.
로컬 관리 보호, state·코드 교환, 암호화·변조 거부, 만료/회전·보존, 병렬 갱신, DB 실패/재시작 격리,
템플릿/링크·200자·한국어 form·result_code·권한/쿼터·UNKNOWN과 기존 Email/Discord를 회귀 검증한다.
PostgreSQL 테스트는 기존 OwnedPostgresSchema를 재사용하고, 캐시된 여러 컨텍스트의 연결 한도 초과를 방지하도록 테스트 풀만 최대 3개로 제한했다.

5C에서는 KAKAO 채널과 비밀값 없는 연결 대상 ID, 채널별 조건/활성 기준/중복키·DB 선점·작업/이력을 신규 마이그레이션으로 연결해야 한다.
Kakao recipient는 이메일 주소가 아닌 연결 계정 식별자여야 한다. retryAfter·UNKNOWN·재연결 필요 상태와 전역/채널 비활성화 정책을 보존해야 한다.
토큰 회전과 메시지 불확실성은 별개이며 새 토큰 발급이 기존 메시지의 실패를 의미하지 않는다.
실제 앱 동의·토큰 발급/갱신·도메인 허용·메시지 표시, 다중 인스턴스 안정성은 NOT VERIFIED다.
Phase 5C·6, 실제 로그인/발송, Commit·Push·배포는 수행하지 않았다.

최종 실행 기록 (2026-10-24): `gradlew.bat build integrationTest --no-daemon --console=plain` **BUILD SUCCESSFUL**.

| 구분 | 통과 | 실패 | 오류 | 건너뜀 |
| --- | ---: | ---: | ---: | ---: |
| 기본/단위 | 259 | 0 | 0 | 0 |
| PostgreSQL 통합 | 105 | 0 | 0 | 0 |
| 합계 | 364 | 0 | 0 | 0 |

기존 282개와 Kakao 신규 82개(기본 70·DB 12)를 포함한다.
신규 테스트는 `KakaoClientTests`, `KakaoOAuthTests`, `KakaoSenderTests`, `KakaoLocalTests`, `KakaoWiringTests`,
`KakaoPersistenceTests`다. DB 없는 기본 웹 컨텍스트에 TokenStore Mock을 추가하고 기존 Flyway 최신 버전 기대값만 V5로 변경했다.
초기 실행의 Mock 요청 설정 오류와 테스트 컨텍스트들의 DB 연결 한도 초과는 수정한 후 전체 재실행으로 통과했다.
PostgreSQL 17.11에서 V1~V5 적용·제약조건·재시작/동시 갱신·DB 저장 실패를 확인했고, 기존 수집/이메일·Discord 회귀도 통과했다.
외부 opt-in 테스트는 이번 작업에서 실행하지 않았으며 위 건수의 건너뜀 0에 포함된 테스트가 아니다.
