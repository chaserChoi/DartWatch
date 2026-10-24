# DartWatch

OpenDART 공시를 수집하고 관심 기업·공시 유형 조건에 맞는 신규 공시를 이메일로 알리는 단일 사용자 Spring Boot Backend입니다.
기업별 공시를 반복 확인하는 수고를 줄이고 정정공시·실패 처리·중복 알림을 일관되게 관리하는 개인 프로젝트입니다.

**Phase 1~4 개발과 Mock 기반 MVP 검증을 완료했습니다.** 실제 3개 기업의 신규 공시를 장시간 수집해 Gmail 수신까지 확인한 운영 검증은 별개입니다.
이번 최종 검증에서는 실제 OpenDART·Gmail을 호출하지 않았습니다. 최종 판정과 미검증 항목은 [PHASE_4](docs/PHASE_4.md)에 있습니다.

추가로 **Phase 5A Discord Webhook 발송 모듈**을 구현했습니다. 기존 자동 파이프라인에는 연결하지 않았으며 실제 Discord 메시지는 보내지 않았습니다.
구조·오류/대기 정책·사람이 1건을 직접 확인하는 방법은 [PHASE_5](docs/PHASE_5.md)에 있습니다.
기본 `DISCORD_ENABLED=false`, `DISCORD_WEBHOOK_URL`은 빈 값이며 실제 값은 실행 환경에만 공급합니다.
연결/응답 timeout은 `DISCORD_CONNECT_TIMEOUT=PT5S`, `DISCORD_READ_TIMEOUT=PT10S`로 조정합니다.
Discord를 켜도 기존 수집/이메일 스케줄러는 Discord를 호출하지 않습니다. 다중 채널 작업 배분은 Phase 5C 후속 작업입니다.

**Phase 5B Kakao OAuth·나에게 보내기 모듈**도 독립적으로 구현했습니다. 기본 `KAKAO_ENABLED=false`,
`KAKAO_LOCAL_CONNECT_ENABLED=false`이며 자동 수집·발송 파이프라인에는 연결하지 않았습니다.
본인 계정 연결, AES-256-GCM 토큰 저장·갱신, Mock 검증과 로컬 수동 확인 절차는 [PHASE_5의 5B 절](docs/PHASE_5.md#phase-5b--kakao-oauth와-나에게-보내기)에 있습니다.
등록 웹 도메인이 미확인 상태여서 링크 설정은 비어 있으며, 설정 전에는 메시지를 차단합니다. 실제 로그인·카카오톡 발송은 검증하지 않았습니다.

## 핵심 기능과 기술 스택

- 기업 고유번호 ZIP/XML 동기화, 기업명·종목코드 검색, 관심 기업 관리.
- 기본 10분 자동 수집, 페이지 검증, 최초/증분 구분, 체크포인트·rcept_no UNIQUE로 중복 방지.
- 공식 A~E 유형 분류와 기업별 알림 조건. F~J를 E로 추정하지 않고 미확인 유형은 NULL로 유지.
- 이메일 작업 생성·DB 선점·제한 재시도·이력. 불확실한 UNKNOWN은 자동 재전송하지 않음.
- 저장 공시 검색·유형 필터·페이지네이션·DART 링크, Swagger UI와 공통 오류 응답.

| 영역 | 프로젝트 고정 버전/기술 |
| --- | --- |
| 언어/빌드 | Java 21, Gradle 8.14.3 Wrapper, Groovy DSL |
| 서버 | Spring Boot 3.5.16, RestClient, Spring Scheduler |
| 영속성 | Spring Data JPA, PostgreSQL 17.11, Flyway V1~V5, Hibernate validate |
| 이메일/문서 | Spring Mail(JavaMailSender), Springdoc OpenAPI 2.9.1 |
| 테스트 | JUnit 5·Mockito(Boot BOM), PostgreSQL·Mock HTTP·폐기용 로컬 SMTP |
| 개발 인프라 | Docker Compose v2. Redis·Kafka·외부 큐 없음 |

Boot BOM으로 의존성 버전을 관리합니다. 서비스 사용자 인증·프론트엔드·다중 사용자·키워드 필터는 현재 범위에 없습니다.

## 아키텍처와 디렉터리

```text
기업 ZIP 동기화 → DB 기업 검색 → 관심 기업/조건 설정
                                  │
자동 수집(10분) → OpenDART 전체 페이지 → 신규 공시 + 체크포인트 원자적 저장
                                  │ 성공 기업만
                              공식 유형 분류
                                  │ 활성 기준/조건 일치
                              PENDING 작업 생성
                                  │
독립 전송 타이머(1분) → DB 선점 PROCESSING → NotificationSender → SMTP
                                  └→ SENT / FAILED / UNKNOWN
```

SMTP와 DB는 별도 트랜잭션입니다. SENT는 SMTP 수락이며 수신함 전달이나 exactly-once를 보장하지 않습니다.

```text
dartwatch/
├── build.gradle, settings.gradle, gradlew(.bat), gradle/wrapper/
├── compose.yaml, .env.example
├── docs/                        # 계획·ERD·단계별 설계와 검증
├── src/main/java/com/dartwatch/
│   ├── api/                     # ProblemDetail·OpenAPI
│   ├── company/, watchlist/
│   ├── opendart/                # RestClient·ZIP/XML·재시도
│   ├── collection/              # 체크포인트·수집·자동 파이프라인
│   ├── disclosure/              # 저장 목록·분류
│   ├── notification/            # 조건·전송·이력·재시도
│   ├── discord/, kakao/         # 독립 발송 모듈·로컬 OAuth·암호화 토큰
│   └── scheduling/, health/
├── src/main/resources/application.yaml, db/migration/V1~V5
└── src/test/java/com/dartwatch/  # 단위·통합·로그·외부 opt-in 테스트
```

요구사항은 [PROJECT_PLAN](docs/PROJECT_PLAN.md), ERD는 [DOMAIN_MODEL](docs/DOMAIN_MODEL.md),
과거 단계 기록은 [Phase 2](docs/PHASE_2.md), [3A](docs/PHASE_3.md), [3B](docs/PHASE_3B.md), [3C](docs/PHASE_3C.md)에 있습니다.
현재 정책은 이 README와 [Phase 4](docs/PHASE_4.md)를 기준으로 합니다.

## 로컬 개발 환경 구축

JDK 21, 실행 중인 Docker 엔진, Docker Compose v2가 필요합니다. JAVA_HOME을 설치한 JDK 21 경로로 설정하고
`java -version`을 확인하세요. Gradle 별도 설치는 필요 없으며 최초 실행에는 의존성 다운로드가 필요합니다.
아래 명령은 프로젝트 루트의 PowerShell 기준입니다. macOS/Linux는 `./gradlew`, 필요 시 `chmod +x gradlew`를 사용하세요.

```powershell
# 최초 한 번만 복사. 기존 .env는 덮어쓰지 않습니다.
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
# .env에서 DB_PASSWORD를 직접 설정한 후 진행
docker compose config --quiet
docker compose up -d --wait postgres
docker compose ps
.\gradlew.bat bootRun
```

Compose는 PostgreSQL만 실행하고 포트는 127.0.0.1:5432에 바인딩합니다. 데이터는 named volume에 보존됩니다.
포트 충돌은 DB_PORT로 조정합니다. 종료는 `docker compose down`이며 데이터 삭제 옵션은 사용하지 않습니다.
기존 볼륨의 DB 계정/비밀번호는 .env 수정만으로 바뀌지 않습니다. 기존 DB 정보를 확인하거나 별도 개발 DB를 사용하세요.
기동 시 Flyway가 마이그레이션하고 JPA가 validate합니다. 기존 V1~V4 SQL은 수정하지 않습니다.
V5는 본인 카카오 연결 1건을 암호화해 저장하는 별도 테이블이며 기존 공시·이메일 데이터는 변경하지 않습니다.

```powershell
.\gradlew.bat build
# .env를 읽도록 프로젝트 루트에서 실행
java -jar build/libs/dartwatch-0.0.1-SNAPSHOT.jar
```

Health: [GET /api/health](http://localhost:8080/api/health), Swagger: [swagger-ui.html](http://localhost:8080/swagger-ui.html),
OpenAPI: [v3/api-docs](http://localhost:8080/v3/api-docs). SERVER_PORT 변경 시 URL도 변경합니다.
Health의 `{"status":"UP"}`는 API 응답 확인이며 DB·OpenDART·SMTP readiness가 아닙니다.

## 환경변수

Spring과 Compose가 루트 .env를 읽으며 OS 환경변수가 우선합니다. 따옴표 없는 KEY=value 형식을 사용하세요.
$, #, 역슬래시 등은 두 파서의 해석이 다르므로 복잡한 비밀번호는 OS 환경변수로 공급하는 편이 안전합니다.
.env와 .env.*는 Git 제외 대상이며 .env.example만 공개합니다. 인증정보가 담긴 전체 Compose 설정 출력은 공유하지 마세요.

| 변수 | 기본값 / 의미 |
| --- | --- |
| DB_HOST / DB_PORT / DB_NAME / DB_USERNAME | localhost / 5432 / dartwatch / dartwatch |
| DB_PASSWORD | 필수. 직접 설정, 예제는 빈 값 |
| SERVER_PORT / OPENDART_API_KEY | 8080 / 빈 값(실제 호출 시 필요) |
| COLLECTION_ENABLED / COLLECTION_INTERVAL / COLLECTION_INITIAL_DELAY | false / PT10M / PT30S |
| COLLECTION_INITIAL_DAYS / COLLECTION_OVERLAP_DAYS | 30 / 3 |
| OPENDART_CONNECT_TIMEOUT / OPENDART_READ_TIMEOUT | 5s / 30s |
| OPENDART_PAGE_SIZE / OPENDART_MAX_PAGES | 100 / 1000 |
| OPENDART_MAX_RETRIES / OPENDART_RETRY_INITIAL_DELAY / OPENDART_RETRY_MAX_DELAY | 2 / PT1S / PT10S |
| OPENDART_RATE_LIMIT_COOLDOWN | PT1H |
| CLASSIFICATION_BATCH_SIZE / CLASSIFICATION_WINDOW_DAYS / CLASSIFICATION_RETRY_INTERVAL | 100 / 30 / PT24H |
| NOTIFICATION_ENABLED / NOTIFICATION_SCHEDULER_ENABLED | false / false |
| NOTIFICATION_SCHEDULER_INTERVAL / NOTIFICATION_SCHEDULER_INITIAL_DELAY | PT1M / PT30S |
| NOTIFICATION_EMAIL_TO / MAIL_FROM | 빈 값. 단일 수신/발신 주소 |
| MAIL_HOST / MAIL_PORT / MAIL_USERNAME / MAIL_PASSWORD | 빈 값 / 587 / 빈 값 / 빈 값 |
| MAIL_AUTH / MAIL_STARTTLS_ENABLED / MAIL_STARTTLS_REQUIRED | true / true / true |
| MAIL_CONNECT_TIMEOUT_MS / MAIL_READ_TIMEOUT_MS / MAIL_WRITE_TIMEOUT_MS | 각각 5000 |
| NOTIFICATION_MAX_RETRIES / NOTIFICATION_RETRY_INTERVAL | 3 / PT10M. 최초 1회 + 최대 3회 추가 시도 |
| NOTIFICATION_PROCESSING_TIMEOUT / NOTIFICATION_BATCH_SIZE | PT10M / 20 |

설정 변경 후 재시작합니다. **수집·전역 메일·자동 전송의 세 활성화 플래그는 모두 기본 false**입니다.
허용 범위와 장애 정책은 [Phase 4](docs/PHASE_4.md)를 참고하세요.

## OpenDART 키와 SMTP 계정 준비

1. [OpenDART 공식 사이트](https://opendart.fss.or.kr/)의 인증키 신청/관리 → 인증키 신청에서 계정을 만들고 키를 신청합니다.
2. 발급/사용 가능 상태와 접근 IP 제한을 확인하고 OPENDART_API_KEY를 로컬에 설정합니다.
   키가 없어도 DB 조회·Health·Swagger·Mock 테스트는 가능하며 실제 동기화/수집은 실패합니다.
3. SMTP 공급자가 허용하는 인증정보와 발신 주소를 준비합니다. Gmail STARTTLS 예시는 MAIL_HOST=smtp.gmail.com,
   MAIL_PORT=587, MAIL_USERNAME은 전체 계정 주소, MAIL_FROM은 해당 계정에서 허용된 주소입니다. 비밀번호와 수신 주소는 직접 설정합니다.
4. Gmail 앱 비밀번호는 2단계 인증이 필요하며 조직/고급 보호 정책 등에 따라 제공되지 않을 수 있습니다.
   계정 로그인 비밀번호를 그대로 넣지 말고 공급자 정책을 확인하세요. 현재 MVP는 SMTP 계정 인증을 사용하며 OAuth 로그인은 없습니다.

공식 안내: [Google 앱 비밀번호](https://support.google.com/accounts/answer/185833?hl=ko),
[Gmail SMTP 설정](https://knowledge.workspace.google.com/admin/gmail/send-email-from-a-printer-scanner-or-app?hl=en).
인증정보는 문서·코드·채팅·로그에 붙여넣지 않습니다. 이번 단계에서 실제 발급·메일 발송은 실행하지 않았습니다.

## 관심 기업 등록과 활성화 순서

처음에는 세 활성화 플래그를 false로 유지합니다. 아래 동기화/수동 수집은 실제 OpenDART를 호출할 수 있습니다.

```powershell
$base = 'http://localhost:8080'
Invoke-RestMethod -Method Post "$base/api/companies/sync"
$company = Invoke-RestMethod "$base/api/companies?query=005930"
$watch = Invoke-RestMethod -Method Post "$base/api/watchlist" -ContentType 'application/json' `
  -Body (@{companyId=$company.items[0].id} | ConvertTo-Json)
Invoke-RestMethod -Method Post "$base/api/collections"
Invoke-RestMethod -Method Put "$base/api/watchlist/$($watch.id)/notification-rules/A" `
  -ContentType 'application/json' -Body '{"enabled":true}'
Invoke-RestMethod -Method Patch "$base/api/watchlist/$($watch.id)/notifications" `
  -ContentType 'application/json' -Body '{"enabled":true}'
```

동기화 후 기업 검색은 DB만 조회합니다. 삼성전자 005930, SK하이닉스 000660, NAVER 035420 등 검색 결과의 DB id로 등록합니다.
corp_code와 stock_code를 바꿔 넣지 않습니다. 결과가 비어 있으면 동기화/검색 결과를 먼저 확인하세요.

- COLLECTION_ENABLED=true로 재시작: 기본 10분 fixed delay로 수집·분류. 메일이 꺼져 있으면 작업 생성/전송 없음.
- SMTP 설정과 기업별 조건을 확인하고 실제 발송을 허용한 뒤 NOTIFICATION_ENABLED=true로 재시작: 적격 작업 생성 가능.
- NOTIFICATION_SCHEDULER_ENABLED=true도 설정: 기본 1분마다 대기/안전한 재시도 작업 자동 처리.
  자동 타이머가 꺼져 있으면 전송은 명시적 dispatch 요청에서만 수행됩니다.

최초 과거 데이터와 활성화 이전/비활성 기간 탐지는 발송하지 않습니다. 기업·조건·수신자 활성 기준보다 최초 저장 시각이 뒤이고
initial_import=false여야 합니다. 최초 기준일 이하 접수일은 늦게 발견해도 과거로 처리합니다.
수동 수집은 즉시 분류/전송하지 않지만 자동 알림이 켜져 있으면 이후 주기의 대상이 될 수 있습니다.

## 주요 REST API

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| GET | /api/health | API 응답 확인 |
| POST | /api/companies/sync?refresh=false | 최초 동기화/명시적 갱신 |
| GET | /api/companies?query=005930&page=0&size=20 | 기업명 또는 종목코드 검색 |
| POST / GET | /api/watchlist | companyId로 등록(201) / 목록 |
| PATCH | /api/watchlist/{id}/monitoring | enabled true/false |
| DELETE | /api/watchlist/{id} | 관심/조건 삭제(204), 공시·발송 이력 보존 |
| POST | /api/collections | 수집만 실행. HTTP 200이어도 본문 실패 가능 |
| GET | /api/disclosures | 저장 공시 조회 |
| POST | /api/watchlist/{watchId}/disclosure-classifications | 미분류 한 배치 A~E 조회 |
| GET | /api/watchlist/{watchId}/notification-rules | 기업별 알림/조건 설정 |
| PUT | /api/watchlist/{watchId}/notification-rules/{type} | A~E, enabled로 등록/수정/해제 |
| PATCH | /api/watchlist/{watchId}/notifications | 기업별 알림 켜기/끄기 |
| GET | /api/watchlist/{watchId}/notification-candidates | 발송 없는 후보 미리보기 |
| POST | /api/watchlist/{watchId}/notification-jobs | PENDING 생성, 전송 없음 |
| POST | /api/notifications/dispatch | 실제 전송 가능. confirmSend=true와 전역 활성화 필수 |
| GET | /api/notifications?status=UNKNOWN&page=0&size=20 | 전체/상태별 이력 |

목록은 `{items,page,size,totalElements}`, 관심 목록은 배열입니다. page는 0 이상, size는 1~100입니다.
공시 필터는 companyId, query(기업명 부분/종목코드 일치), type(공식 대문자 A~J)이며 AND로 결합합니다.
query는 입력 최대 200자·공백만 불가, %/_/!는 글자 그대로 검색합니다. 유형 생략 시 미분류도 포함합니다.
접수일·접수번호 내림차순, 없는 기업/페이지는 빈 목록이며 항목마다 DART 링크가 있습니다.
오류는 application/problem+json의 status/detail/instance/code를 확인합니다. 입력 400, 없음 404,
중복/잠금 409, DB/설정/한도 503, OpenDART 종류에 따라 502/504입니다. 상세 DTO·오류는 Swagger에 있습니다.

## 빌드와 테스트

```powershell
# DB 없이 기본 테스트·실행 JAR 빌드
.\gradlew.bat build
# 최종 전체 검증: 실제 PostgreSQL 필요, 외부 API/메일 제외
$env:RUN_LIVE_SMTP_TEST = 'false'
docker compose up -d --wait postgres
.\gradlew.bat clean build integrationTest --no-daemon --console=plain
```

기본 test는 API 계약·도메인·ZIP/XML·Mock HTTP·스케줄러·로그·폐기용 루프백 SMTP를 검증합니다.
integrationTest는 소유한 `dw_test_<uuid>` PostgreSQL 스키마에서 Flyway·제약조건·E2E·장애/복구를 검증하고 정리합니다.
스키마 생성/삭제 권한이 있는 개발 DB를 사용하세요. 운영 DB를 테스트 연결 대상으로 사용하지 않습니다.
DB가 없으면 통합 테스트는 실패하며 자동 생략하지 않습니다. 테스트 작업의 자동 수집/전송 설정은 강제 false입니다.

실제 외부 검증은 일반 테스트에서 제외된 opt-in입니다. 이번 최종 검증에서는 둘 다 실행하지 않았습니다.

- `gradlew.bat liveTest`: 실제 OpenDART ZIP 1회·목록 최대 1회, 키·DB·네트워크 필요. SMTP 제외.
- `gradlew.bat smtpSmokeTest`: 실제 이메일 발송. 실행 승인과 OS RUN_LIVE_SMTP_TEST=true 및 SMTP 설정이 모두 필요.
  이 Smoke Test의 MAIL_FROM·NOTIFICATION_EMAIL_TO는 System.getenv로 읽으므로 두 주소도 OS 환경변수로 제공해야 합니다.
  직접 JavaMailSender를 호출하므로 NOTIFICATION_ENABLED=false만으로 발송이 막히지 않습니다.

보고서: build/reports/tests/test/index.html, build/reports/tests/integrationTest/index.html.
최종 실행 건수·A~E 시나리오 근거·PASS/NOT VERIFIED 판정은 [PHASE_4](docs/PHASE_4.md)에 있습니다.

## 장애 확인과 운영

1. docker compose ps와 DB 접속 설정을 확인합니다. 비밀번호가 담긴 전체 Compose 설정은 출력하지 않습니다.
2. GET /api/watchlist의 최근 시도/성공 시각·마지막 성공 조회일·오류 코드를 확인합니다.
3. 수집 HTTP 200만 믿지 않고 COMPLETED/PARTIAL_FAILED/FAILED와 기업별 결과를 확인합니다.
4. 알림은 GET /api/notifications?status=FAILED 또는 status=UNKNOWN으로 확인합니다. 조회는 발송하지 않습니다.

runId·watchId/corpCode·deliveryId/rceptNo로 추적합니다. INFO는 시작/성공/집계, WARN은 장애·불확실성,
ERROR는 예상하지 못한 API 오류입니다. 키·비밀번호·수신자·선점 토큰·원본 SQL/인증 URI는 기록하지 않습니다.

| 오류/상태 | 정책과 조치 |
| --- | --- |
| AUTHENTICATION / INVALID_REQUEST | API 자동 재시도 없음. 키/접근 IP/파라미터 확인 |
| RATE_LIMIT | 현재 추가 호출 중단·기본 1시간 공유 cooldown. 만료가 공급자 한도 해제를 보장하지 않음 |
| UPSTREAM_SYSTEM / HTTP_SERVER / NETWORK / TIMEOUT | 페이지별 기본 2회 지수 백오프+jitter. 다음 수집에서 실패 범위 재확인 |
| PERSISTENCE | 공시와 체크포인트 함께 롤백. DB 복구 뒤 기존 기준으로 재수집 |
| FAILED | 연결 실패/명시적 4xx만 기본 10분 간격·총 4회 이내. 인증/5xx/상한 초과는 자동 재시도 없음 |
| PROCESSING | 재선점 금지. 기본 10분 뒤 다음 활성 dispatch에서 UNKNOWN 격리 |
| UNKNOWN | 자동 재발송 금지. 공급자 기록 등으로 확인 필요. 강제 초기화 API 없음 |
| SENT | SMTP 수락. 재전송 금지. 최종 수신함 도착의 증거는 아님 |

dispatch는 발송을 일으킬 수 있으므로 상태 확인을 위해 호출하지 않습니다. 비활성화는 관측된 PENDING/재시도 FAILED를 취소하고,
재활성화 시 과거 탐지를 일괄 발송하지 않습니다. SMTP 수락 뒤 DB 저장 응답이 유실되면 통계는 unknown이어도 DB는 SENT일 수 있습니다.

## 제한사항과 향후 계획

- 앱 인스턴스 하나가 전제입니다. 분산 락·외부 큐·인증·공개 인터넷 운영 구성은 제공하지 않습니다.
- 최초 30일/이후 성공일 이전 3일 중첩 조회입니다. 더 늦은 과거 게시·1000페이지 초과 백필은 보장하지 않습니다.
- A~E에서 찾지 못한 공시는 미분류입니다. F~J 추가 분류 호출은 하지 않습니다. 분류는 기본 100개/30일 배치입니다.
- API cooldown은 메모리 상태여서 재시작 시 초기화됩니다. 반복 재시작으로 제한을 우회하지 않습니다.
- 실제 10분 장시간 운영·OS 강제 종료·다중 인스턴스·최종 메일 전달은 NOT VERIFIED이며 exactly-once를 보장하지 않습니다.
- Phase 5A/5B: Discord/Kakao 독립 모듈. Phase 5C 후보: 채널별 조건·작업·이력과 안전한 전송 배분.
  Phase 6 후보: 인증·대시보드·관측성·대량 조회 개선.
- 운영/배포 전 비밀정보 관리·TLS/접근 통제·DB 백업/복원·모니터링·승인된 외부 E2E를 별도 준비합니다.

현재 Phase 5B까지 구현했습니다. Phase 5C·6, 실제 카카오 로그인·메시지 발송, Commit·Push·배포는 수행하지 않았습니다.
