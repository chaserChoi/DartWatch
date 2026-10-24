# Phase 3B — 이메일 전송·이력·재시도

기존 3A 분류·후보 필터와 `NotificationDelivery`를 확장했다.
추가 의존성은 Boot BOM에 맞춘 `spring-boot-starter-mail:3.5.16`뿐이다.
`NotificationSender` 인터페이스의 구현체는 `EmailNotificationSender` 하나다.
수집·분류에서 자동 작업 생성/발송으로 연결하는 3C와 전송 스케줄러는 이번에 구현하지 않는다.

## 안전한 사용과 REST API

`NOTIFICATION_ENABLED=false`가 기본값이다. 이 값은 `COLLECTION_ENABLED`와 독립적이다.
메일 설정이 없어도 서버가 기동하며 기동 시 SMTP 연결 검사를 하지 않는다.
기동, 수집, 분류, 조건 변경, 후보 조회, 이력 조회는 SMTP 전송을 실행하지 않는다.
이번 작업에서는 외부 SMTP나 실제 수신자에게 이메일을 발송하지 않는다.

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| POST | `/api/watchlist/{id}/notification-jobs` | 적격 후보를 현재 수신자의 PENDING 작업으로 생성, `{enqueued}`. 전송 없음 |
| POST | `/api/notifications/dispatch` | `{"confirmSend":true}` 필수. 설정 활성화 시 실제 전송 가능 |
| GET | `/api/notifications?status=FAILED&page=0&size=20` | 전체 또는 상태별 이력, 최대 페이지 크기 100 |

작업 생성·전송 API는 전역 설정이 꺼져 있으면 503, 확인 없는 전송 요청은 400이다.
SMTP 호출과 DB 선점은 별개로 처리하며 수집 트랜잭션은 호출하지 않는다.
동일 작업의 강제 재시도·SENT/UNKNOWN 초기화 API는 제공하지 않는다.
설정된 간격 후 **다음 dispatch 요청**이 재시도 대상도 처리한다. 이 단계에는 자동 발송/재시도 타이머가 없다.

## 시작 기준과 작업 생성

3A와 동일하게 관심 등록·모니터링·기업별 알림·A~E 조건·조건 활성 시각·최초 수집 구분·최초 저장 시각을 검사한다.
여기에 수신자의 **전역 이메일 활성 기준**을 추가한다.

- `notification_email_settings`에 정규화된 수신자별 사용 여부와 활성 시각을 기록한다.
- 앱 준비 완료 시와 작업 생성/전송 요청 전에 설정을 동기화한다. SMTP는 호출하지 않는다.
- 처음 전역 사용을 켜거나, 관측된 비활성 상태에서 다시 켜거나, 수신자를 변경하면 새 기준을 적용한다.
- 계속 활성인 같은 수신자로 재시작할 때는 기존 기준을 유지한다. 재시작 자체로 적격 대기 작업을 잃지 않는다.
- `disclosure.collected_at`이 기업·조건·전역 기준보다 모두 뒤이고 `initial_import=false`인 공시만 작업을 생성한다.
- 분류가 늦어져도 최초 저장 시각을 바꾸지 않으므로 지속 활성 상태였던 적격 공시는 누락하지 않는다.
- 전역 비활성화는 PENDING과 재시도 대기 FAILED를 CANCELLED로 기록한다. SENT·UNKNOWN·영구 실패 이력은 보존한다.
- 전송 전에도 현재 조건을 다시 확인한다. 조건 변경·관심 삭제·수신자 변경으로 부적격해진 대기 작업은 취소한다.

설정 변경은 앱 실행 중 관측되거나 재시작되어야 DB에 반영된다. 원격 다중 인스턴스 설정 동기화 기능은 없다.
실제 SMTP 호출이 이미 진행 중일 때 그 호출을 원자적으로 취소할 수는 없다.

## DB 선점과 상태

```text
PENDING ──선점 커밋──> PROCESSING ──SMTP 수락──> SENT
                          ├── 명시적 실패 ──> FAILED ──간격·상한 내 재시도──> PROCESSING
                          └── 결과 불확실 ──> UNKNOWN (자동 재시도 금지)
PENDING / 재시도 FAILED ──설정·조건 해제──> CANCELLED
```

`SENT`는 SMTP 서버의 정상 수락이며 실제 수신함 도착이나 열람을 뜻하지 않는다.

1. DB 트랜잭션에서 `FOR UPDATE OF n SKIP LOCKED`로 기한이 된 작업 1개를 선점한다.
2. PROCESSING, 누적 시도 횟수, 시도 시각과 임의 UUID 선점 토큰을 커밋한다.
3. DB 트랜잭션 밖에서 SMTP를 호출한다.
4. 별도 트랜잭션에서 현재 PROCESSING 상태와 토큰이 모두 같은 경우에만 결과를 저장한다.

동시 작업자는 같은 작업을 함께 선점하지 않는다. 단순 JVM 플래그에만 의존하지 않는다.
결과 저장은 상태·토큰을 검사하므로 오래된 작업자가 복구된 UNKNOWN을 SENT로 덮어쓸 수 없다.
서버 중단 등으로 PROCESSING이 기본 10분 이상 남으면 다음 dispatch가 UNKNOWN으로 격리한다.
그 이전에는 PROCESSING을 재선점하지 않으며, 복구 시 자동 재발송하지 않는다.
긴 작업의 자동 lease 연장은 없으므로 운영 시 처리 제한 시간을 SMTP 타임아웃보다 충분히 길게 둔다.

SMTP 성공 후 DB 결과 저장에 실패하면 UNKNOWN 기록을 별도 시도하고, DB도 불가능하면 PROCESSING을 남겨 이후 복구하게 한다.
같은 실행에서 SMTP를 다시 호출하지 않는다. SMTP와 DB는 하나의 트랜잭션이 아니므로 **exactly-once를 보장하지 않는다**.

## 중복·재시도 정책

- `(disclosure_id, channel, recipient)` UNIQUE와 INSERT의 중복 제외·`ON CONFLICT DO NOTHING`을 함께 사용한다.
- 이메일은 단일 주소만 허용하고 소문자로 정규화한다. 표시 이름, 복수 수신자, CR/LF는 거부한다.
  MVP 중복 방지 정책으로 local part도 소문자로 정규화한다.
- 이미 SENT·UNKNOWN·CANCELLED인 작업은 새 작업으로 다시 생성하지 않는다.
- 기본 최대 재시도 3회는 **최초 1회 + 재시도 3회 = 총 4번 선점**이다. 시도 횟수는 SMTP 예약 선점 횟수로 기록한다.
- 명시적 SMTP 4xx 거절·연결 실패는 FAILED와 다음 시도 시각을 기록한다.
- SMTP 5xx 거절, 인증·설정·메시지 구성 오류는 자동 재시도하지 않는다.
- 인증·설정 오류가 발생하면 같은 dispatch의 나머지 작업 처리를 중단한다.
- DATA 후 응답 유실·일반 타임아웃·일부 수락·판별할 수 없는 MailException은 UNKNOWN이다.
  라이브러리의 validUnsent 목록만으로 DATA 후 미수락을 확신하지 않는다.
- UNKNOWN, SENT, 재시도 상한 초과, 영구 실패는 자동 처리 대상에서 제외한다.
- 영구 실패의 설정을 수정해도 같은 작업을 자동 초기화하지 않는다. 원인 확인과 수동 운영 판단이 필요하다.

작업 한 행은 공시·수신자·채널별 논리적 발송 이력이다. 누적 횟수와 최근 결과를 갱신하며 모든 시도별 별도 감사 테이블은 추가하지 않았다.
현재 수신자는 단일 환경설정 값이고, 다중 사용자·주소록 모델은 없다.

## V4 마이그레이션

`V4__add_email_delivery_and_retry.sql`을 추가하고 V1~V3는 수정하지 않는다.

- 기존 `notification_delivery`에 nullable 수신자, 다음 시도 시각, 재시도 여부, UUID 선점 토큰을 추가한다.
- SENDING 상태를 PROCESSING 체계로 대체하되 **기존 SENDING 행은 UNKNOWN으로 이전**한다.
- 기존 SENT는 SENT로 보존한다. 수신자를 모르는 기존 행은 NULL로 보관하며 현재 수신자로 임의 backfill하지 않는다.
- NULL 수신자 행은 선점 대상에서 제외하고, 동일 공시·채널의 새로운 작업 생성도 보수적으로 막는다.
- 기존 NULL 수신자 행의 중복 방지를 위해 partial UNIQUE 인덱스도 유지한다.
- 상태·시각·횟수·선점 토큰의 CHECK 제약과 기한 조회 인덱스를 추가한다.
- `notification_email_settings`에 수신자별 전역 활성 기준을 추가한다. 새 비즈니스 Entity는 만들지 않았다.

## SMTP 설정

실제 SMTP 활성화·발송은 사용자 승인 후에만 실행한다. `.env.example`에는 실제 주소·비밀번호를 넣지 않는다.

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| `NOTIFICATION_ENABLED` | `false` | 작업 생성·이메일 전송 활성화 |
| `MAIL_HOST` | 빈 값 | SMTP 호스트 |
| `MAIL_PORT` | `587` | SMTP 포트 |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | 빈 값 | SMTP 인증정보 |
| `MAIL_FROM` | 빈 값 | 발신 주소 |
| `NOTIFICATION_EMAIL_TO` | 빈 값 | 단일 수신 주소 |
| `MAIL_AUTH` | `true` | SMTP 인증 |
| `MAIL_STARTTLS_ENABLED` | `true` | STARTTLS 사용 |
| `MAIL_STARTTLS_REQUIRED` | `true` | STARTTLS 필수 |
| `MAIL_CONNECT_TIMEOUT_MS` | `5000` | 연결 타임아웃 |
| `MAIL_READ_TIMEOUT_MS` | `5000` | 읽기 타임아웃 |
| `MAIL_WRITE_TIMEOUT_MS` | `5000` | 쓰기 타임아웃 |
| `NOTIFICATION_MAX_RETRIES` | `3` | 최초 시도 이후 최대 0~10회 재시도 |
| `NOTIFICATION_RETRY_INTERVAL` | `PT10M` | 재시도 간격, 최소 1분 |
| `NOTIFICATION_PROCESSING_TIMEOUT` | `PT10M` | PROCESSING 불확실 복구 기준, 최소 1분 |
| `NOTIFICATION_BATCH_SIZE` | `20` | 생성·전송 배치 상한, 1~100 |

텍스트 MIME의 제목과 본문은 UTF-8이다. 기업명·보고서명·공식 유형·접수일·접수번호 기반 DART 링크를 포함한다.
Spring Mail/Jakarta Mail의 원본 예외·SMTP 응답 문구·비밀번호·수신 주소를 로그에 출력하지 않는다.
DB에는 정해진 안전한 오류 코드만 저장하고 이력 API는 미확인 과거 오류 문구를 LEGACY_FAILURE로 마스킹한다.
메일 debug/wire logging과 앱 기동 시 연결 검사는 꺼져 있다.

공식 근거: [Boot 3.5 Mail 설정·타임아웃](https://docs.spring.io/spring-boot/3.5/reference/io/email.html),
[Angus SMTPSendFailedException](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/SMTPSendFailedException.html).

## 검증과 다음 단계

```powershell
.\gradlew.bat build integrationTest
# 기존 OpenDART 회귀 검증(메일 비활성화)
.\gradlew.bat liveTest
```

Gradle 테스트는 전역 메일 설정을 강제로 비활성화한다. 발송 테스트에서만 Mock NotificationSender 또는
독립적으로 생성한 JavaMailSender와 127.0.0.1 폐기용 SMTP를 사용한다.
실제 외부 SMTP·실제 수신자에게는 발송하지 않는다.

검증 항목은 한글 MIME, SMTP 수락/거절/응답 유실, 기본 비활성화, DB 선점 경쟁,
재시도 간격·상한, UNKNOWN 제외, 재시작·오래된 선점 토큰, 기존 V3 데이터 이전, 상태별 페이지 이력이다.
3C에서 자동 수집·분류·작업 생성·처리 주기를 연결해야 한다.
Phase 4의 운영 경보·UNKNOWN 검토 절차·상세 감사 로그·장시간 장애 복구는 이번 범위에 추가하지 않는다.

실행 결과 (2026-10-24):

- 최종 Java 21 `build integrationTest` 성공. 기본/단위 76개, PostgreSQL 통합 63개, 실패·오류·생략 0개.
- 기존 OpenDART `liveTest` 1개 성공을 포함해 총 140개 통과. 실제 유형별 API나 외부 SMTP 검증을 추가 실행하지 않았다.
- 폐기용 루프백 SMTP 2개 테스트로 UTF-8 한글 MIME과 DATA 이후 응답 유실의 UNKNOWN 처리를 확인했다.
- 초기 통합 검증에서 결과 저장 CASE 표현식의 PostgreSQL 시간 타입 오류를 발견해 명시적 캐스트로 수정한 뒤 재검증했다.
- V1~V3 무변경, 기존 SENT·SENDING 데이터의 V4 이전, 개발 DB V4 적용 성공 및 잔여 테스트 스키마 0개를 확인했다.
- 소스·문서·예제·테스트 XML에서 설정된 인증키·메일 비밀번호가 노출되지 않았음을 확인했다.
- 실제 외부 SMTP·수신함 도착·장시간 운영·다중 인스턴스 전체 파이프라인은 미검증이다.
- 실제 이메일 발송, 3C·Phase 4, Commit·Push·배포는 진행하지 않았다.
