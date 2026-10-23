# DartWatch

OpenDART 기업 공시를 수집하고 관심 조건에 맞는 공시를 이메일로 알리는 단일 사용자 Backend 프로젝트입니다.
요구사항의 기준은 [프로젝트 계획서](docs/PROJECT_PLAN.md)입니다.

현재 구현 범위는 **Phase 1A: 초기 환경 구성 + Phase 1B: 도메인·영속성 계층**입니다.
5개 Entity와 Repository, 제약조건, 알림 상태 모델을 구현했습니다. ERD와 상세 설계는 [도메인 모델](docs/DOMAIN_MODEL.md)을 참고하세요.
공시 수집, 외부 API, 스케줄러, 이메일, 인증 및 프론트엔드는 구현하지 않았습니다.

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
    │   │   ├── company/          # 기업·코드 매핑, Repository
    │   │   ├── watchlist/        # 관심 기업, Repository
    │   │   ├── disclosure/      # 공시·유형, Repository
    │   │   └── notification/    # 조건·발송 이력·상태·채널, Repository
    │   └── resources/
    │       ├── application.yaml
    │       └── db/migration/V1__create_domain_tables.sql
    └── test/java/com/dartwatch/
        ├── DartWatchApplicationTests.java
        ├── PostgresApplicationTests.java
        ├── DomainPersistenceTests.java
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
Flyway가 최초 실행 시 5개 도메인 테이블·제약조건을 생성하고 이후 JPA가 매핑을 검증합니다.
스키마 변경은 버전 SQL로 관리합니다.

## 빌드와 테스트

```powershell
# DB 없이 웹 애플리케이션 컨텍스트, Health, OpenAPI, Swagger UI 검증 및 실행 JAR 생성
.\gradlew.bat clean build

# 실제 PostgreSQL을 먼저 실행하고, DB 연결·전체 컨텍스트·실제 HTTP 요청 검증
docker compose up -d --wait postgres
.\gradlew.bat integrationTest

# 빌드한 JAR 실행 (프로젝트 루트에서 실행)
java -jar build/libs/dartwatch-0.0.1-SNAPSHOT.jar
```

기본 `test`는 DB/JPA 자동 설정을 제외한 4개 smoke test와 기업 코드·알림 상태의 도메인 단위 테스트입니다.
기본 빌드 성공만으로 DB 연결이 검증되지는 않습니다.
`integrationTest`는 실제 PostgreSQL에서 `SELECT 1`, Health/OpenAPI/Swagger HTTP 응답과
5개 Entity의 매핑·Repository 조회·중복/FK/CHECK 제약조건·관심 삭제 시 이력 보존·낙관적 잠금 충돌을 검증합니다.
도메인 영속성 테스트는 임시 스키마를 만들어 사용하고 종료 시 제거합니다.
DB 또는 비밀번호가 준비되지 않으면 실패하며, 기본 `build`에는 포함하지 않았습니다.
H2나 Testcontainers 의존성 및 임의의 테스트 비밀번호를 추가하지 않았습니다.

테스트 보고서는 `build/reports/tests/test/index.html` 및 `build/reports/tests/integrationTest/index.html`에 생성됩니다.
