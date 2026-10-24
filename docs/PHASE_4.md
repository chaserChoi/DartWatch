# Phase 4 — 수집·이메일 안정성

Phase 4A와 4B를 구현했다. 아래 수집 내용은 4A 구현 시점의 기록이며 이메일 변경은 뒤의 **Phase 4B** 절을 기준으로 한다.
Phase 4C·4D, 신규 인프라·의존성·Entity·마이그레이션은 포함하지 않는다.

## 기존 보장과 보완점

| 장애/상황 | 기존 보장 | 이번 조치 |
| --- | --- | --- |
| 중간 페이지 실패·페이지 메타데이터 변경 | 전체 결과를 반환하지 않음, 체크포인트 유지 | 유지, 실패 페이지 재시도와 DB 복구 테스트 추가 |
| 기업별 API/DB 실패 | 기업별 트랜잭션·오류 기록, 다른 기업 계속 실행 | 유지, 오류 기록까지 실패하는 상황 검증 |
| 저장 중 DB 예외 | 기업 공시 배치와 성공 기준일 함께 롤백 | 유지, 실제 PostgreSQL CHECK 오류로 중간 삽입 롤백 검증 |
| 수집 도중 종료 | prepare에서 최초 기준일·시도 시각 커밋, 성공 기준일은 complete에서만 갱신 | 유지, 서비스 재생성 후 DB 기준 복원 검증 |
| 반복 조회·정정공시 | rcept_no UNIQUE + ON CONFLICT DO NOTHING, 기존 데이터 불변 | 유지, 장애 후 반복 수집/기존 데이터 보존 검증 |
| 일시적 API 오류 | 즉시 실패하고 다음 수집 때 복구 | 페이지/기업 ZIP 요청별 제한 재시도 추가 |
| 요청 한도 초과 | 현재 수집 실행의 나머지 기업/분류 중단 | 공유 클라이언트의 실행 간 cooldown 추가 |
| 스케줄러 실패·겹침 | fixed delay, collector/pipeline guard, 예외 포착 | 유지, 실패 후 실제 다음 타이머 실행 검증 |

## 오류 분류와 재시도

[OpenDART 공식 공시검색 가이드](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001)를 확인했다.
공식 코드의 의미와 애플리케이션 정책을 구분한다. 아래 재시도 횟수·대기는 DartWatch의 MVP 정책이다.

| 응답/장애 | 오류 종류 | 처리 |
| --- | --- | --- |
| 000 / 013 | 정상 / 빈 결과 | 공시 목록은 정상 처리. 2페이지 이후 013은 불완전 목록으로 실패 |
| 010·011·012·901 / HTTP 401·403 | AUTHENTICATION | 즉시 실패, 현재 실행의 남은 기업/분류 중단 |
| 020 / HTTP 429 | RATE_LIMIT | 즉시 실패, 현재 실행 중단 및 공유 cooldown |
| 021·100 | INVALID_REQUEST | 요청 범위/파라미터 문제, 즉시 실패 |
| 014·101 등 기타 거절 | API_REJECTED | 즉시 실패 |
| 800·900 | UPSTREAM_SYSTEM | 제한 재시도 |
| HTTP 5xx | HTTP_SERVER | 제한 재시도 |
| 기타 비정상 HTTP | HTTP | 재시도 안 함 |
| 연결 오류 / 타임아웃 | NETWORK / TIMEOUT | 제한 재시도 |
| 잘못된 JSON/XML·상충하는 페이지·중복 접수번호·상한 초과 | INVALID_RESPONSE | 재시도 안 함, 다음 실행에서 범위 전체 재확인 |
| 종료 interrupt | INTERRUPTED | interrupt 유지, 추가 기업·분류 호출 중단 |

`OpenDartRequestPolicy`는 기존 RestClient 내부에 연결했다. 기업 ZIP/XML·전체 공시·유형별 조회가 동일 정책과
cooldown을 공유한다. 응답 본문의 status 검사를 재시도 안쪽에서 수행하므로 HTTP 200의 800/900도 처리한다.
원본 예외·응답 message·인증된 URI는 로그/응답에 첨부하지 않는다. 재시도 로그는 오류 종류·횟수·대기만 기록한다.

기본 최초 요청 1회 + 재시도 최대 2회다. 대기는 `min(최대 지연, 초기 지연 × 2^재시도순번)`의 50~100%로
무작위화한다(equal jitter). 기본 첫 대기 0.5~1초, 다음 1~2초다. 실제 요청 시간은 기존 연결/읽기 타임아웃을 따른다.
설정 상한으로 재시도는 최대 5회, 회당 대기는 최대 30초로 제한한다. 0회면 즉시 실패 정책으로 되돌릴 수 있다.

재시도 단위는 **현재 HTTP 페이지**다. 앞 페이지는 메모리에 유지하며 성공한 재시도 결과를 한 번만 추가한다.
전체 페이지 메타데이터·접수번호 검증을 통과한 후에만 DB 저장한다. 재시도 소진/메타데이터 변경 시 부분 결과를
저장하지 않으며, 다음 수집 실행에서 DB 체크포인트를 기준으로 1페이지부터 다시 조회한다.
수집 서비스나 스케줄러에서 별도 중첩 재시도를 추가하지 않아 호출량이 곱절로 증가하지 않게 했다.

020/429 이후 기본 1시간 동안 모든 후속 클라이언트 요청은 외부 통신 없이 RATE_LIMIT으로 실패한다.
차단 중 재호출로 cooldown을 연장하지 않는다. 만료 후 첫 요청을 허용하고 다시 한도 오류면 새 cooldown을 시작한다.
이는 공급자 한도 해제 시각의 추정이 아니다. 공식 문서상 한도는 키별로 다를 수 있어 해제를 보장하지 않는다.
현재 HTTP Retry-After는 별도로 해석하지 않으며, 공급자 설정에 맞춰 cooldown을 조정해야 한다.
이미 전송 중인 다른 요청은 취소하지 않는다. cooldown은 단일 프로세스 메모리이므로 재시작하면 초기화된다.

## 체크포인트와 스케줄러

기존 `CollectionStore`와 V2 수집 상태를 그대로 사용한다.

1. prepare: 최초 종료일을 한 번 고정하고 최근 시도 시각을 커밋한다.
2. 모든 페이지 HTTP 호출: DB 트랜잭션 밖에서 실행한다.
3. complete: 관심/활성/기준을 재확인한 뒤 공시 배치와 성공 조회일·성공 시각을 원자적으로 커밋한다.
4. 실패: 성공 조회일/성공 시각을 유지하고 안전한 오류 코드만 별도 기록한다.

DB 장애로 오류 코드 기록까지 실패해도 로그만 남기고 guard를 해제한다. 실패 로그조차 DB에 남지 않을 수 있으나
성공 체크포인트를 임의로 전진시키지 않는다. DB 자체 재시도는 추가하지 않으며 다음 실행에서 DB 복구를 재확인한다.
COMMIT 후 응답 유실로 성공/실패 판단이 불확실해도 다음 조회는 DB의 실제 성공 기준과 UNIQUE를 사용한다.

기본 첫 범위는 고정 최초 종료일 이전 30일, 이후는 `[마지막 성공일 - 3일, 실행 당일]`이다.
중단 후에도 당일 기준 최근 며칠로 축소하지 않는다. 최초 실패 복구 도중 `COLLECTION_INITIAL_DAYS`를 줄이지 않는다.
기존 접수번호의 메타데이터·collected_at·initial_import는 갱신하지 않는다. 정정공시는 별도 접수번호로 보존한다.

`COLLECTION_ENABLED` 기본 false와 10분 fixed delay를 유지한다. 이전 자동 작업이 끝난 뒤 10분을 기다린다.
수동/자동 동시 수집은 기존 collector AtomicBoolean으로 거절하고 자동 후속 분류까지 기존 pipeline guard로 보호한다.
일반 기업별 실패는 다른 기업을 계속 처리하며, 인증/설정/한도/종료 interrupt는 추가 요청을 중단한다.
타이머 콜백은 예외를 포착하므로 다음 실행이 취소되지 않는다. 재시작 시 guard는 초기화되고 DB 기준을 다시 읽는다.
다중 인스턴스 잠금은 추가하지 않았다.

Phase 3 이메일 로직·발송 API·발송 조건·트랜잭션은 변경하지 않았다. 수집이 실패한 기업은 후속 분류/알림 생성을
실행하지 않는다. 기존에 적격 판정을 받아 대기 중인 이메일 작업은 독립 스케줄러의 기존 정책을 따른다.

## 설정

| 환경변수 | 기본값 | 허용 범위 |
| --- | --- | --- |
| OPENDART_MAX_RETRIES | 2 | 0~5, 최초 요청 제외 추가 시도 |
| OPENDART_RETRY_INITIAL_DELAY | PT1S | 1ms~30초 |
| OPENDART_RETRY_MAX_DELAY | PT10S | 1ms~30초, 초기 지연보다 작으면 이 상한 우선 |
| OPENDART_RATE_LIMIT_COOLDOWN | PT1H | 1분~1일 |

기존 연결 5초/읽기 30초·페이지 크기·페이지 상한·수집 주기/조회 범위 설정은 유지한다. 설정 변경은 재시작 후 반영한다.
일반 transient 오류는 기업/요청별 재시도만 제한하며 전체 공급자 장애에 대한 circuit breaker는 추가하지 않았다.

## 검증과 한계

```powershell
$env:RUN_LIVE_SMTP_TEST='false'
.\gradlew.bat build integrationTest --no-daemon --console=plain
```

외부 OpenDART는 Mock HTTP 또는 Mockito로 대체한다. PostgreSQL은 기존 로컬 컨테이너와 소유한
`dw_test_<uuid>` 임시 스키마를 사용한다. 운영 행·기존 Flyway SQL은 수정하지 않는다.
DB 장애 주입용 CHECK는 임시 스키마에만 적용하고 finally에서 제거한다.
종료 복구는 prepare 후 complete/fail 없이 서비스 객체를 재생성해 검증하며 실제 OS 프로세스 강제 종료 실험은 아니다.

실행 결과 (2026-10-24):

- Java 21 `build integrationTest` 성공. 기본/단위 119개 통과, 실패·오류 0개, 1개 건너뜀.
- PostgreSQL 통합 76개 통과, 실패·오류·건너뜀 0개. 합계 195개 통과, 1개 건너뜀.
- 건너뛴 1개는 작업 전부터 있던 미추적 `LiveSmtpSmokeTest`다. 파일은 변경하지 않고
  `RUN_LIVE_SMTP_TEST=false`로 명시적으로 실행을 막았다.
- 기존 Phase 3 테스트를 포함한 회귀 검증, 신규 재시도·백오프·한도 차단·페이지 실패·DB 롤백·복구 검증을 통과했다.
- 기존 폐기용 루프백 SMTP 테스트는 실행했고 외부 SMTP 발송은 하지 않았다.
- `liveTest`는 실행하지 않았다. 실제 OpenDART 호출도 없으며 공식 가이드만 조회했다.
- PostgreSQL 컨테이너 healthy, Flyway V1~V4 성공 상태, 테스트 종료 후 남은 `dw_test_` 스키마 0개를 확인했다.
- 기존 마이그레이션과 `notification` 프로덕션 코드 변경 없음, `git diff --check` 통과.

## 남은 제한과 Phase 4B 주의사항

- 실제 공급자 장애·실제 SMTP·장시간 운영·프로세스 강제 종료/DB 컨테이너 중지 실험은 별도 검증이 필요하다.
- 중첩 3일보다 늦게 게시된 과거 공시는 자동 복구를 보장하지 않는다. 기간별 백필/감사는 추후 별도 설계한다.
- 1000페이지 상한을 넘는 긴 중단/대량 공시는 실패로 유지한다. 범위 분할 백필은 이번에 추가하지 않았다.
- 한도 cooldown은 재시작 간 영속화하지 않으므로 반복 재시작을 이용한 호출은 피한다.
- Phase 4B에서 수집 재시도를 이메일 재시도로 연결하지 않는다. 기존 SENT/UNKNOWN·활성 기준·DB claim 정책을 유지한다.
- 한도 차단 중 수동 분류/기업 동기화도 실패할 수 있다. 오류 코드와 체크포인트를 확인하고 불필요한 재실행을 피한다.

## Phase 4B — 이메일 알림 안정성

### 기존 구조 분석

`NotificationService`라는 클래스는 없으며 `NotificationDispatchService`가 생성/전송을 조정한다.
`NotificationWorkStore`는 짧은 DB 트랜잭션, `NotificationSender`/`EmailNotificationSender`는 실제 SMTP,
`NotificationScheduler`는 준비 완료 후 기본 1분 fixed delay 처리를 담당한다.
`NotificationDelivery`/Repository와 V4 제약조건·토큰·수신자별 전역 활성 기준을 재사용했다.

| 요구사항 | 기존 충족 내용 | 4B 조치 |
| --- | --- | --- |
| 중복 생성 | 공시·채널·정규화 수신자 UNIQUE, EXISTS 조건, ON CONFLICT | 유지, 여러 조건+동시 생성 통합 검증 |
| 과거/비활성 기간 제외 | initial_import·collected_at·기업/유형/전역 활성 기준 | 유지, 지연 분류와 재활성화 후 실제 작업 생성 검증 |
| 중복 전송 | SKIP LOCKED 선점 커밋 후 SMTP, PROCESSING+UUID 결과 CAS | 유지, SMTP 대기 중 동시 dispatch 검증 |
| 제한 재시도 | 안전한 FAILED만 기한/시도 상한 내 선점 | 유지, 재시작 전후 기한/수락 검증 |
| 상한 설정 감소 | 선점 쿼리에서 상한 이상 제외 | 잔여 retryable/next_attempt_at도 정리하도록 보완 |
| 불확실성 | 타임아웃·일부 수락·오래된 PROCESSING → UNKNOWN | 혼합 I/O 예외·억제된 예외·탐색 상한 초과도 보수적으로 격리 |
| 결과 저장 실패 | UNKNOWN 저장 시도, 실패 시 PROCESSING 유지 후 격리 | 실제 DB 쓰기 오류·커밋 응답 유실 모의 검증 |
| 실제 SMTP 테스트 | 환경변수 true일 때 실행될 수 있는 일반 테스트 | smtp-live 태그 + 별도 smtpSmokeTest 작업으로 분리 |

### 변경 내용과 재시도

- `EmailNotificationSender`: cause/nextException/메시지별 예외와 suppressed 예외를 확인한다.
  일반 I/O·읽기/쓰기 타임아웃이 명시적 SMTP 오류와 섞이면 UNKNOWN을 우선한다.
  100개 이내에서 예외 그래프를 완전히 검사하지 못해도 UNKNOWN이다.
- `MailConnectException`은 연결 성립 실패를 나타내므로 내부 connect timeout을 포함해 CONNECTION_FAILED로 제한 재시도한다.
  독립적인 일반 timeout은 전송 시점을 확정할 수 없어 UNKNOWN이다. 일부 validSent 주소가 있으면 항상 UNKNOWN이다.
- 명시적 4xx 거절과 확실한 연결 실패만 재시도한다. 인증 오류·5xx 거절·구성/메시지 오류는 영구 FAILED로 유지한다.
- 기존 최초 1회 + 최대 3회 재시도, 간격 10분, 최대 설정 0~10회와 최소 간격 1분을 유지한다.
  SMTP 실패의 backoff를 새로 추가하지 않았다. 기존 고정 간격과 배치 상한으로 호출량을 제한한다.
- `stopExhaustedRetries()`는 다음 dispatch에서 현재 상한에 도달한 FAILED 행의 retryable을 false,
  next_attempt_at을 NULL로 바꾼다. 마지막 원인·시도 횟수·시각은 유지한다.
  상한을 나중에 다시 높여도 이미 종료한 작업을 자동 부활시키지 않는다.
- SENT·UNKNOWN·CANCELLED·영구 실패는 생성/선점/자동 재전송 대상으로 전환하지 않는다.

예외 구조의 공식 근거는 [Spring MailSendException](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/mail/MailSendException.html),
[Angus SMTPSendFailedException](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/SMTPSendFailedException.html)이다.
혼합된 불확실성에서 UNKNOWN을 우선하는 것은 중복 위험을 줄이기 위한 DartWatch의 보수적 정책이다.

### 중단/DB 장애 복구

| 중단 지점 | 다음 실행 정책 |
| --- | --- |
| 작업 생성 커밋 전 | 생성이 롤백되면 이후 적격 후보 생성에서 재확인 |
| PENDING 커밋 후 | 같은 수신자·연속 활성 기준을 유지한 재시작에서 기한이 된 작업을 한 번 선점 |
| 선점 COMMIT 전 실패 | 롤백된 PENDING/FAILED만 이후 재선점 가능, SMTP 호출 없음 |
| 선점 COMMIT 응답 유실 | 선점 성공을 확인하지 못한 호출자는 SMTP를 실행하지 않음. DB가 PROCESSING이면 timeout 후 UNKNOWN |
| PROCESSING/SMTP 호출 중 종료 | timeout 전 재선점 금지, 기본 10분 이후 다음 dispatch에서 UNKNOWN 격리 |
| SMTP 수락 후 DB 기록 전 종료 | PROCESSING을 재발송하지 않고 이후 UNKNOWN 격리 |
| SENT 기록 실패 | 별도 UNKNOWN 저장 시도. 이것도 실패하면 PROCESSING을 유지하고 이후 격리 |
| SENT COMMIT 성공 후 응답 유실 | fallback UNKNOWN 저장은 상태·토큰 CAS에서 거절됨. DB SENT를 보존하고 재발송하지 않음 |
| 늦은 작업자의 결과 도착 | 복구된 UNKNOWN의 상태/토큰과 맞지 않아 덮어쓰기 금지 |

SMTP 호출은 공시 저장 및 선점/결과 DB 트랜잭션 밖에서 실행한다. 전송 결과를 기록하지 못했다고
SMTP를 다시 호출하거나 PENDING으로 되돌리지 않는다. 결과 저장 응답이 유실된 dispatch의 응답 통계는 unknown으로
나올 수 있지만 실제 DB에는 SENT가 커밋되었을 수 있다. 이후 상태 판단은 DB 이력을 기준으로 한다.

복구는 전역 메일 활성화 상태의 dispatch에서 수행한다. 자동 처리 타이머가 꺼져 있으면 안전한 복구도 다음 명시적
dispatch까지 기다린다. 전역 비활성화 상태에서 강제로 전송/복구 실행을 허용하는 API는 추가하지 않았다.
PROCESSING은 기존 상태별 이력 API로 조회할 수 있다. 복구 시각은 timeout 경과 후 다음 실행 시점이다.
SMTP와 DB는 하나의 트랜잭션이 아니므로 **exactly-once 전달을 보장하지 않는다**.
SENT는 SMTP 서버 수락이며 실제 수신함 도착·열람 확인은 아니다.

### SMTP 설정과 실제 테스트 분리

기존 환경변수 이름과 기본값을 유지한다. 연결/읽기/쓰기 각각 5000ms, SMTP 인증과 STARTTLS enable/required는 true,
UTF-8 MIME 제목·본문, mail debug 및 기동 연결 검사는 false다. `.env`나 비밀번호 값은 수정하지 않았다.
`NOTIFICATION_ENABLED=false`, `NOTIFICATION_SCHEDULER_ENABLED=false`가 기본이며 `COLLECTION_ENABLED`와 독립적이다.

`LiveSmtpSmokeTest`의 발송 본문과 RUN_LIVE_SMTP_TEST 조건은 유지하고 smtp-live 태그를 추가했다.
일반 `test`/`build`/`integrationTest`와 OpenDART `liveTest`는 이 태그를 제외하므로 환경변수가 true여도
일반 작업에 포함되지 않는다. 실제 발송은 **명시적으로 승인받은 별도 실행에서만** 아래 두 조건으로 가능하다.

1. OS 환경변수 `RUN_LIVE_SMTP_TEST=true`와 기존 MAIL_* / NOTIFICATION_EMAIL_TO 설정.
2. 별도 `gradlew.bat smtpSmokeTest` 작업 실행. 캐시된 성공으로 대체하지 않는다.

이번에는 smtpSmokeTest·liveTest를 실행하지 않는다. 기존 실제 Gmail 통과 사실은 사용자 제공 이력이며 재검증하지 않는다.
개발 검증은 Mock NotificationSender/JavaMailSender와 전달 없이 데이터를 폐기하는 127.0.0.1 테스트 서버만 사용한다.

### DB와 API

새 마이그레이션·컬럼·제약조건·Entity·REST API는 없다. 기존 V1~V4와 중복 방지 제약을 유지한다.
상한 정리는 정상 dispatch의 짧은 UPDATE이며 FAILED 이력을 보존한다. 강제 SENT/UNKNOWN 재시도 API는 추가하지 않는다.
Phase 4A의 수집/재시도 로직은 이번 4B에서 변경하지 않았다.

### 검증

`PhaseFourBIntegrationTests`는 소유한 임시 PostgreSQL 스키마에서 실제 서비스/트랜잭션/제약조건을 사용한다.
SMTP는 Mock이고 CHECK 오류로 결과 저장 실패를 주입한다. 커밋 응답 유실은 실제 트랜잭션 커밋 직후 예외를 던지는
위임 Store로 모의한다. 서비스 객체 재생성과 DB 재조회로 재시작 복구 상태를 검증하며 실제 OS 프로세스를 종료하지 않는다.

실행 결과 (2026-10-24):

- Java 21 `build integrationTest` 성공. 기본/단위 124개, PostgreSQL 통합 88개, **총 212개 통과**.
  실패·오류·건너뜀은 모두 0개다. 기존 Phase 4A 회귀 테스트도 포함했다.
- 4B 신규 검증은 SMTP 예외 4개, 스케줄러 예외 후 재실행 1개, PostgreSQL 중단/동시 처리/지연 분류/DB 장애 12개다.
- 일반 테스트 XML에 LiveSmtpSmokeTest가 포함되지 않았음을 확인했다.
  smtpSmokeTest·liveTest는 실행하지 않았으며 외부 Gmail·OpenDART를 호출하지 않았다.
- 기존 폐기용 루프백 SMTP 2개 테스트로 UTF-8 한글과 DATA 후 응답 유실을 회귀 검증했다.
- Flyway V1~V4 성공 상태와 테스트 종료 후 잔여 `dw_test_` 스키마 0개를 확인했다.
- 기존 마이그레이션 변경 없음, `git diff --check` 통과. Commit·Push·배포·Phase 4C/4D는 수행하지 않았다.

### 남은 제한과 Phase 4C 주의사항

- 실제 Gmail 재발송·수신함 전달·장시간 SMTP 장애·OS 강제 종료/DB 네트워크 단절 실험은 이번 검증에 없다.
- UNKNOWN은 자동 재시도하지 않는다. 서버 로그/공급자 기록으로 확인 후 운영 판단이 필요하며 강제 재전송 기능은 제공하지 않는다.
- 처리 timeout은 SMTP 전체 작업보다 충분히 길게 둔다. 오래 실행 중인 SMTP를 취소하거나 선점 lease를 연장하지 않는다.
- 알림/조건 변경과 이미 진행 중인 SMTP 사이에는 원자적 취소가 불가능하다. 현재 전송 전 재검증과 이후 대기 작업 취소를 유지한다.
- Phase 4C에서 응답 통계만으로 전달 여부를 단정하지 않고 DB 상태와 불확실성을 함께 표시한다.
- 수집 재시도로 SENT/UNKNOWN을 초기화하거나 전역/기업/조건의 활성 기준을 과거로 되돌리지 않는다.
- 기존 도메인 보조 메서드는 실제 전송 워커가 아니다. 자동 전송/재시도는 계속 WorkStore 선점·기한·상한 경로만 사용한다.
