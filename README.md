# DartWatch

OpenDART 기업 공시를 수집하고 관심 조건에 맞는 공시를 이메일로 알리는 단일 사용자 Backend 프로젝트입니다.
요구사항의 기준은 [프로젝트 계획서](docs/PROJECT_PLAN.md)입니다.

현재 **Phase 3C: 자동 수집·분류·작업 생성·전송 처리 스케줄러 연결**까지 구현했습니다.
기존 5개 Entity를 재사용하며 ERD는 [도메인 모델](docs/DOMAIN_MODEL.md),
API·수집 기준·설정·검증 방법은 [Phase 2 가이드](docs/PHASE_2.md)를 참고하세요.
분류·조건 API와 활성 기준은 [Phase 3A 가이드](docs/PHASE_3.md)에 정리했습니다.
이메일 전송·상태·SMTP 설정은 [Phase 3B 가이드](docs/PHASE_3B.md)에 정리했습니다.
자동 파이프라인과 실행 설정은 [Phase 3C 가이드](docs/PHASE_3C.md)를 참고하세요.
인증·프론트엔드는 구현하지 않았습니다.

## 환경과 버전

| 항목 | 버전 / 선택 이유 |
| --- | --- |
| Java | 21, Gradle Toolchain으로 컴파일·테스트 버전 고정 |
| Spring Boot | 3.5.16, Java 21 및 요청된 JUnit 5와 호환되는 안정 3.x 릴리스 |
| Gradle | 8.14.3, Boot 3.5의 지원 범위인 8.x 사용, Wrapper와 배포 ZIP SHA-256 포함 |
| Springdoc OpenAPI | 2.9.1, Spring Boot 3용 안정 2.x 릴리스 |
| PostgreSQL | 17.11, 지원 중인 17 계열의 공식 안정 이미지로 고정 |
| Spring Data JPA / JDBC / JUnit / Mockito | 3.5.13 / 42.7.11 / 5.12.2 / 5.17.0, Boot 3.5.16 BOM으로 관리 |
| Flyway core / PostgreSQL 모듈 | 11.7.2, Boot BOM으로 관리 |
| Spring Mail Starter | 3.5.16, 기존 Boot BOM으로 관리 |

버전 확인 기준: 2026-10-24. Boot 4 대신 JUnit 5 요구사항과 맞는 Boot 3.5를 선택했습니다.
Spring dependency-management 플러그인 대신 Gradle의 기본 BOM 지원을 사용합니다.
Web, JPA, Springdoc, PostgreSQL JDBC, Test Starter와 DB 마이그레이션용 Flyway core·PostgreSQL 모듈을 사용합니다.
Flyway 버전도 기존 Boot BOM으로 관리합니다.

공식 근거:

- [Spring Boot 3.5 시스템 요구사항](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Spring Boot BOM 관리 버전](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html)
- [Springdoc v2 문서 및 호환성](https://springdoc.org/v2/)
- [Springdoc 2.9.1 릴리스](https://github.com/springdoc/springdoc-openapi/releases/tag/v2.9.1)
- [Gradle 8.14.3 릴리스](https://docs.gradle.org/8.14.3/release-notes.html)
- [PostgreSQL 버전 지원 정책](https://www.postgresql.org/support/versioning/)

## 구조

```text
dartwatch/
├── build.gradle / settings.gradle
├── gradlew / gradlew.bat / gradle/wrapper/
├── compose.yaml
├── .env.example / .gitignore
├── docs/PROJECT_PLAN.md
└── src/
    ├── main/
    │   ├── java/com/dartwatch/
    │   │   ├── DartWatchApplication.java
    │   │   ├── health/HealthController.java
    │   │   ├── api/              # 안전한 공통 오류 응답
    │   │   ├── opendart/         # RestClient, ZIP/XML, 외부 응답·오류
    │   │   ├── collection/       # 수집·기준일 저장·스케줄러
    │   │   ├── company/          # 기업·코드 매핑, Repository
    │   │   ├── watchlist/        # 관심 기업, Repository
    │   │   ├── disclosure/      # 공시·유형, Repository
    │   │   └── notification/    # 조건·발송 이력·상태·채널, Repository
    │   └── resources/
    │       ├── application.yaml
    │       └── db/migration/    # V1 도메인, V2 수집 상태·메타데이터
    └── test/java/com/dartwatch/
        ├── DartWatchApplicationTests.java
        ├── PostgresApplicationTests.java
        ├── DomainPersistenceTests.java
        ├── PhaseTwoIntegrationTests.java
        ├── OpenDartLiveTests.java
        ├── collection/         # 수집·스케줄러 테스트
        ├── opendart/           # ZIP/XML·Mock HTTP 테스트
        ├── company/CompanyTests.java
        └── notification/NotificationDeliveryTests.java
```

기능별 패키지를 사용합니다. 현재는 Entity와 Repository를 각 기능 패키지에 두고,
API·비즈니스 로직을 추가할 때 controller/service/client 책임을 분리합니다.

## 로컬 실행

JDK 21과 Docker Compose v2가 필요합니다. `JAVA_HOME`을 JDK 21 설치 경로로 설정하고 Docker 엔진을 실행하세요.
아래 명령은 프로젝트 루트에서 PowerShell 기준으로 실행합니다. macOS/Linux에서는 `./gradlew`를 사용합니다.

1. `Copy-Item .env.example .env`를 **최초 한 번만** 실행하고 `.env`의 `DB_PASSWORD`를 직접 설정합니다.
   실제 비밀번호나 API Key는 예제에 포함하지 않았습니다. `.env`와 `.env.*`는 Git에서 제외됩니다.
2. `docker compose up -d --wait postgres`
3. `.\gradlew.bat bootRun`

Spring은 루트 `.env`를 properties 형식으로 읽고, Compose도 동일 파일을 사용합니다.
OS 환경변수가 파일 값보다 우선합니다. `.env`는 따옴표 없이 `KEY=value`로 작성하세요.
두 파서의 `$`, `#`, 역슬래시 등 특수문자 처리가 다르므로, 특수문자가 포함된 비밀번호는 `.env` 대신
OS의 `DB_PASSWORD` 환경변수로 공급하세요. 이 경우 `.env`의 `DB_PASSWORD`는 빈 값이어도 됩니다.
비밀번호를 포함한 `docker compose config` 전체 출력은 공유하지 마세요. 설정 검사에는 `docker compose config --quiet`를 사용하세요.

| 환경변수 | 기본값 | 용도 |
| --- | --- | --- |
| `DB_HOST` | `localhost` | 호스트에서 실행하는 앱의 DB 주소 |
| `DB_PORT` | `5432` | 로컬 DB 포트 및 JDBC 포트 |
| `DB_NAME` | `dartwatch` | 데이터베이스 이름 |
| `DB_USERNAME` | `dartwatch` | 데이터베이스 사용자 |
| `DB_PASSWORD` | 없음 (필수) | Compose 초기화 및 앱 연결 비밀번호 |
| `SERVER_PORT` | `8080` | API 서버 포트 |

Compose는 PostgreSQL만 실행하며 DB 포트는 `127.0.0.1`에만 공개합니다.
기존 로컬 PostgreSQL과 충돌하면 `DB_PORT`를 변경하세요. PostgreSQL 데이터는 named volume에 보존됩니다.
DB 초기화 변수는 빈 데이터 볼륨에서만 적용되므로 기존 볼륨의 계정/비밀번호는 `.env` 수정만으로 변경되지 않습니다.
종료는 `docker compose down`을 사용합니다.

## 접근 주소

- Health: [GET /api/health](http://localhost:8080/api/health), 응답 `{"status":"UP"}`
- Swagger UI: [swagger-ui.html](http://localhost:8080/swagger-ui.html)
- OpenAPI JSON: [v3/api-docs](http://localhost:8080/v3/api-docs)

Health는 API 서버의 응답 여부만 확인합니다. DB 상태를 나타내는 readiness API는 아닙니다.
JPA는 `ddl-auto=validate`, `open-in-view=false`입니다.
Flyway가 5개 도메인 테이블·제약조건 및 기업 동기화 완료 메타데이터 테이블을 생성하고 이후 JPA가 매핑을 검증합니다.
스키마 변경은 버전 SQL로 관리합니다.

V3는 공시 분류 확인 시각과 기업/조건별 활성 기준을 추가합니다. 기존 V1·V2는 유지합니다.
기존 데이터에 대해 알림을 자동 활성화하지 않습니다.

## Phase 3A 사용

- `POST /api/watchlist/{id}/disclosure-classifications`: 미분류 공시를 공식 A~E 조회 결과로 분류합니다.
  이미 확인된 유형은 재사용하며 F~J를 E로 추정하지 않습니다. 자동 수집 성공 후에도 같은 분류 서비스를 사용합니다.
- `GET /api/watchlist/{id}/notification-rules`: 기업별 설정과 조건을 조회합니다.
- `PUT /api/watchlist/{id}/notification-rules/A`: `{"enabled":true}` 형식으로 A~E 조건을 등록·수정합니다.
- `PATCH /api/watchlist/{id}/notifications`: `{"enabled":true}`로 기업별 후보 필터를 켭니다(기본 false).
- `GET /api/watchlist/{id}/notification-candidates`: 최초 수집·활성화 이전·비활성 기간 데이터를 제외한 후보를 페이지 조회합니다.

이 API는 발송 없는 미리보기입니다. 알림 작업·SMTP 호출·발송 이력은 생성하지 않습니다.
상세 조회 전략·활성화 시각 정책·분류 설정은 [Phase 3A 가이드](docs/PHASE_3.md)를 참고하세요.

## Phase 3B 사용

기본 `NOTIFICATION_ENABLED=false`이며 앱 기동·수동 수집·분류·후보 조회로 이메일이 전송되지 않습니다.
실제 SMTP 설정과 전송 활성화는 사용자 승인 후 진행하세요. 이번 검증은 Mock과 폐기용 루프백 SMTP만 사용했습니다.

- `POST /api/watchlist/{id}/notification-jobs`: 현재 설정된 수신자의 적격 후보를 PENDING으로 저장합니다. 전송하지 않습니다.
- `POST /api/notifications/dispatch`: `{"confirmSend":true}`와 전역 활성화가 모두 필요합니다.
  이 API는 실제 이메일 전송을 일으킬 수 있으며 PENDING·재시도 가능한 FAILED 작업만 제한된 배치로 처리합니다.
- `GET /api/notifications?status=FAILED&page=0&size=20`: 상태별 페이지 이력, 시도 횟수·다음 시도 시각·오류 코드를 확인합니다.

최초 전역 활성화와 재활성화 시각을 DB에 기록하여 그 이전에 탐지된 공시를 일괄 발송하지 않습니다.
재시도 기본값은 최초 1회 + 최대 3회, 간격 10분입니다. UNKNOWN·SENT·영구 실패는 자동 재시도하지 않습니다.
3C의 전송 스케줄러를 켜면 기한이 지난 작업을 자동 처리합니다. 타이머가 꺼져 있으면 명시적 dispatch에서 처리합니다.
V4는 수신자별 UNIQUE·DB 선점 토큰·재시도 시각·전역 활성 기준을 추가하고 기존 V1~V3와 이력을 보존합니다.

## Phase 3C 자동 실행

세 설정은 모두 기본 `false`입니다.

- `COLLECTION_ENABLED=true`: 기본 10분마다 수집 → 성공 기업 분류 → 메일 전역 설정이 켜진 경우 작업 생성.
- `NOTIFICATION_ENABLED=true`: 적격 작업 생성·전송 전역 허용. SMTP 설정과 기존 활성 기준 검사를 적용합니다.
- `NOTIFICATION_SCHEDULER_ENABLED=true`: 메일 전역 설정도 켜져 있을 때 기본 1분마다 대기·기한이 된 재시도 작업 처리.

전송 주기는 `NOTIFICATION_SCHEDULER_INTERVAL=PT1M`, 초기 지연은 `NOTIFICATION_SCHEDULER_INITIAL_DELAY=PT30S`로 조정합니다.
수집·전송 타이머는 앱 준비 완료 후 독립적으로 실행하며 실패한 후속 단계가 이미 저장한 공시를 롤백하지 않습니다.
기존 `POST /api/collections`는 계속 수집만 수행합니다. 자동 전송이 켜져 있으면 그 데이터도 이후 자동 주기의 검사 대상이 될 수 있습니다.
실제 SMTP 사용·자동 발송 활성화는 사용자 승인 후 진행하세요. 검증용 환경에서는 메일 설정 두 플래그를 false로 유지합니다.
상세 정책은 [Phase 3C 가이드](docs/PHASE_3C.md)에 정리했습니다.

## Phase 2 사용 순서

1. `.env` 또는 OS 환경변수에 `OPENDART_API_KEY`를 설정합니다. 예제 키 값은 비워 두었습니다.
2. DB와 앱을 실행한 뒤 기업 목록을 한 번 동기화합니다.
   `Invoke-RestMethod -Method Post http://localhost:8080/api/companies/sync`
3. `GET /api/companies?query=005930` 또는 URL 인코딩한 기업명으로 검색합니다.
4. 검색 결과의 DB `id`로 `POST /api/watchlist`에 `{"companyId":1}` 형식의 JSON을 보냅니다.
5. `POST /api/collections`로 활성 관심 기업을 즉시 수집합니다. 응답의 전체·기업별 상태를 확인하세요.
6. `.env`의 `COLLECTION_ENABLED=true`로 설정하고 앱을 재시작하면 기본 10분 fixed delay로 자동 수집·분류합니다.

기업 검색은 OpenDART를 호출하지 않습니다. 기업 동기화 완료 후 같은 동기화 요청도 다운로드를 생략합니다.
최신 기업 정보를 다시 내려받을 때만 `POST /api/companies/sync?refresh=true`를 사용합니다.
자동 수집은 기본 비활성화이며 API Key 없이도 Health·Swagger·DB 조회는 사용할 수 있습니다.
수집 조회 범위와 API 목록은 [Phase 2 가이드](docs/PHASE_2.md)에 정리했습니다.

## Phase 4A 수집 안정성

OpenDART 시스템 장애·HTTP 5xx·연결 오류·타임아웃은 기본 최대 2회 추가 재시도합니다.
지수 백오프와 jitter를 적용하며 인증·파라미터 오류는 즉시 실패합니다.
본문 020 또는 HTTP 429 후 기본 1시간은 기업 동기화·수집·분류의 추가 호출을 차단합니다.
`OPENDART_MAX_RETRIES`, `OPENDART_RETRY_INITIAL_DELAY`, `OPENDART_RETRY_MAX_DELAY`,
`OPENDART_RATE_LIMIT_COOLDOWN`으로 조정합니다.
페이지/저장 실패 시 기존 성공 기준을 유지하고 다음 수집에서 같은 범위를 다시 확인합니다.
이메일 정책과 DB 구조는 변경하지 않았습니다. 상세 설계·한계는 [Phase 4A 가이드](docs/PHASE_4.md)를 참고하세요.

## Phase 4B 이메일 안정성

공시·채널·수신자별 UNIQUE와 DB 선점 토큰을 유지합니다. 오래된 PROCESSING과 불확실한 전송 결과는 UNKNOWN으로
격리하고 SENT/UNKNOWN은 자동 재발송하지 않습니다. 복합 SMTP/I/O 오류의 불확실성 판정을 보강하고,
재시도 상한을 낮춘 경우 기존 FAILED 대기의 재시도 표시와 다음 시각도 정리합니다.
중단 복구·결과 저장 실패·동시 생성/전송 검증은 [Phase 4 가이드](docs/PHASE_4.md)에 기록했습니다.

실제 Gmail Smoke Test는 `smtp-live` 태그로 일반 테스트와 분리했습니다. 별도 `smtpSmokeTest` 작업과
`RUN_LIVE_SMTP_TEST=true`가 모두 필요하며, 실제 발송 승인을 받은 경우에만 실행합니다.

## Phase 4C 저장 공시 조회와 운영 품질

`GET /api/disclosures`는 외부 API 호출 없이 저장된 공시를 조회합니다.

| 파라미터 | 의미 |
| --- | --- |
| `companyId` | 선택, 기업 DB 식별자 |
| `query` | 선택, 기업명 부분 검색 또는 종목코드 일치. 공백 제외 1~200자 |
| `type` | 선택, 공식 대문자 코드 A~J. 생략하면 미분류도 포함 |
| `page`, `size` | 기본 0/20, 페이지는 0 이상·크기는 1~100 |

필터는 AND로 결합하며 접수일·접수번호 내림차순입니다. 없는 기업/페이지는 빈 목록을 반환합니다.
응답은 기존 `{items,page,size,totalElements}` 형식이며 기업 정보·접수번호·유형·최초 수집 여부·탐지 시각·DART 원문 링크를 제공합니다.
예: `GET /api/disclosures?query=005930&type=A&page=0&size=20`.

Swagger에 실제 엔드포인트 설명·태그·필수 요청 값·페이지 범위·주요 ProblemDetail 오류를 정리했습니다.
공통 오류는 기존 `code`를 유지하며 누락 파라미터·잘못된 JSON·404/405/406/415·DB 장애도 같은 형식으로 반환합니다.
수집/전송 로그는 `runId`로 묶이며 기업·공시/작업 식별자, 건수, 시도, 소요 시간, 안전한 오류 코드만 기록합니다.
API Key·수신 이메일·SMTP 비밀번호·선점 토큰·원본 예외와 SQL 상세는 운영 로그에서 제외합니다.
최신 검증 결과와 남은 제한은 [Phase 4 가이드](docs/PHASE_4.md)를 참고하세요.

## 빌드와 테스트

```powershell
# DB 없이 웹 애플리케이션 컨텍스트, Health, OpenAPI, Swagger UI 검증 및 실행 JAR 생성
.\gradlew.bat clean build

# 실제 PostgreSQL을 먼저 실행하고, DB 연결·전체 컨텍스트·실제 HTTP 요청 검증
docker compose up -d --wait postgres
.\gradlew.bat integrationTest

# 명시적 실제 OpenDART 검증: 키·DB·네트워크 필요, ZIP 1회와 목록 최대 1회 호출
.\gradlew.bat liveTest

# 빌드한 JAR 실행 (프로젝트 루트에서 실행)
java -jar build/libs/dartwatch-0.0.1-SNAPSHOT.jar
```

기본 `test`는 4개 smoke test, 도메인·ZIP/XML·Mock HTTP·수집·스케줄러 단위 테스트입니다.
기본 빌드 성공만으로 DB 연결이 검증되지는 않습니다.
`integrationTest`는 실제 PostgreSQL에서 `SELECT 1`, Health/OpenAPI/Swagger HTTP 응답과
5개 Entity의 매핑·Repository 조회·중복/FK/CHECK 제약조건·관심 삭제 시 이력 보존·낙관적 잠금 충돌을 검증합니다.
Phase 2 통합 테스트는 외부 API를 Mock하고 기업 동기화·REST 관리·초기/증분 수집·실패 후 기준일 보존을 검증합니다.
도메인 영속성 테스트는 임시 스키마를 만들어 사용하고 종료 시 제거합니다.
DB 또는 비밀번호가 준비되지 않으면 실패하며, 기본 `build`에는 포함하지 않았습니다.
H2나 Testcontainers 의존성 및 임의의 테스트 비밀번호를 추가하지 않았습니다.
모든 Gradle 테스트 작업에서는 자동 수집·메일 전역 설정·자동 전송 타이머를 강제로 끕니다.
실제 외부 호출은 기본 테스트에서 제외된 `liveTest`의 최소 OpenDART 회귀 검증에서만 수행합니다.

테스트 보고서는 `build/reports/tests/test/index.html` 및 `build/reports/tests/integrationTest/index.html`에 생성됩니다.
