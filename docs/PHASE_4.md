# Phase 4 최종 보고 — 안정성·검증·MVP 판정

검증일: 2026-10-24. Phase 4A~4D를 완료하고 Phase 5·6은 진행하지 않았다.
이 문서는 최신 기술 기준이다. 이전 단계의 구현 기록은 PHASE_2.md, PHASE_3.md, PHASE_3B.md, PHASE_3C.md를 참고한다.

## 1. 최종 판정

**기능 구현 및 Mock/로컬 SMTP·PostgreSQL 통제 환경의 MVP: PASS.**
계획서의 실제 운영 문구인 “3개 이상 기업의 신규 공시를 중복 없이 이메일로 수신”은 이번에 외부 발송하지 않았으므로
**NOT VERIFIED**다. 개발 완료와 실제 운영 인수를 구분하며, 실운영까지 검증된 무조건적인 완료로 판단하지 않는다.

PASS는 아래 명시한 검증 환경에서 직접 실행한 근거가 있다는 뜻이다. FAIL은 재현된 미해결 결함,
NOT VERIFIED는 실행하지 않았거나 관측할 수 없는 범위다. 현재 실행한 테스트의 미해결 실패는 없다.

| MVP 요구사항 | 판정 | 이번 검증 근거/범위 |
| --- | --- | --- |
| 관심 기업 3개 이상 관리 | PASS | 4D 테스트: Mock 삼성전자·SK하이닉스·NAVER 검색/등록/목록/모니터링 |
| 기업 고유번호·종목코드 매핑 | PASS | ZIP/XML·Entity·DB 제약 및 3개 기업의 문자열/선행 0 보존 |
| OpenDART 공시 목록 조회 | PASS | Mock HTTP RestClient의 요청·응답·페이지·오류 검증. 실제 서버는 이번 미호출 |
| 스케줄러 기반 자동 수집 | PASS | 짧은 주기 실제 Scheduled 콜백·준비 상태·중복 guard·예외 후 재실행 |
| 공시 중복 저장 방지 | PASS | rcept_no UNIQUE·ON CONFLICT·반복 수집 PostgreSQL 테스트 |
| 최초/증분 수집 구분 | PASS | 고정 초기 기준·initial_import·성공 체크포인트·중첩 범위 |
| 공시 유형별 분류 | PASS | A~E 유형별 전체 페이지/rcept_no 매핑·미분류/F~J 비추론 |
| 기업별 알림 조건 | PASS | 조건/기업/모니터링 활성화와 비활성 기간·지연 분류 검사 |
| 이메일 전송 구조 | PASS | Mock 발송과 실제 JavaMailSender의 폐기용 127.0.0.1 SMTP·UTF-8 수락 |
| 중복 알림 방지 | PASS | 수신자/채널/공시 UNIQUE·동시 생성/선점·SENT/UNKNOWN 제외 |
| 이메일 실패 처리 | PASS | 인증/4xx/5xx/timeout·상한/기한·DB 저장 실패·UNKNOWN 테스트 |
| API 장애 후 공시 복구 | PASS | 페이지 중간 실패·부분 실패·DB 롤백·중단 상태에서 기준 복원 |
| 공시 목록 REST API | PASS | 기업/유형 AND 필터·정렬·페이지·NULL·링크·잘못된 입력 |
| PostgreSQL 통합 | PASS | 실제 PostgreSQL 연결·Flyway V1~V4·제약조건·E2E |
| Swagger UI/OpenAPI | PASS | 실제 HTTP UI 응답·모든 Controller 경로/메서드·DTO·상태·페이지 계약 |
| README/개발 문서 | PASS | 현재 설정/경로/실행 명령·운영 정책·한계로 최종 정리 |
| 이번 실제 OpenDART·Gmail 연동 | NOT VERIFIED | 외부 태그 테스트 미실행, 실발송 없음 |
| 실제 3기업 신규 공시의 최종 이메일 수신 | NOT VERIFIED | Mock 수락과 실제 수신함 도착은 구분 |
| 실제 10분 주기의 장시간 운영 | NOT VERIFIED | 타이머는 짧은 주기 테스트로 검증 |
| OS 강제 종료/실제 DB 네트워크 단절 | NOT VERIFIED | 저장 상태·DB CHECK 오류·위임 Store 예외로 모의 |
| 다중 인스턴스 전체 안정성 | NOT VERIFIED | 단일 앱 MVP. DB 선점 경쟁 검증을 전체 분산 안정성으로 확장하지 않음 |
| Exactly-once/외부 SMTP 최종 전달 보장 | NOT VERIFIED | 보장 대상이 아님. SMTP와 DB는 하나의 트랜잭션이 아님 |

## 2. Phase 4 개발 결과

| 단계 | 결과 |
| --- | --- |
| 4A | 공통 OpenDART 요청 정책, 페이지별 제한 재시도·지수 백오프/jitter, 영구 오류 제외·한도 cooldown. 기존 원자적 저장·체크포인트 유지 |
| 4B | 혼합 SMTP/I/O 불확실성 보수 판정, 낮아진 재시도 상한 정리, 중단/커밋 응답 유실·동시 처리 검증, 실제 SMTP 테스트 분리 |
| 4C | 저장 공시 GET API, 공통 ProblemDetail 보완, 추적 가능한 안전한 로그, 전체 Swagger/REST 계약과 PostgreSQL 테스트 격리 |
| 4D | 기존 A~E 시나리오 재사용, 3기업 최종 통합 테스트 1개 추가, clean 전체 검증·체크섬/기본값/문서 정리·최종 판정 |

4D의 프로덕션 Java 코드·DB 스키마·의존성 변경은 없다. 설정 예제는 기존 분류 환경변수만 보완한다.
기존 Flyway V1~V4는 수정하지 않았다. 운영 데이터 삭제, Commit·Push·배포는 수행하지 않았다.

## 3. OpenDART 재시도와 수집 체크포인트

### 오류와 호출량

| 응답/장애 | 정책 |
| --- | --- |
| 000 / 첫 페이지 013 | 정상 / 빈 목록. 뒤 페이지 013은 불완전 결과로 실패 |
| 010·011·012·901 / HTTP 401·403 | AUTHENTICATION. 재시도 없이 현재 기업 이후 추가 호출 중단 |
| 020 / HTTP 429 | RATE_LIMIT. 재시도 없이 현재 실행 중단, 공유 cooldown |
| 021·100 / 기타 API 거절 / HTTP 기타 오류 | INVALID_REQUEST / API_REJECTED / HTTP. 재시도 없음 |
| 800·900 / HTTP 5xx / 연결 오류 / timeout | UPSTREAM_SYSTEM / HTTP_SERVER / NETWORK / TIMEOUT. 제한 재시도 |
| 잘못된 XML/JSON·페이지 메타데이터·중복 페이지·용량/페이지 상한 초과 | INVALID_RESPONSE. 재시도 없이 다음 수집에서 전체 범위 재확인 |
| 종료 interrupt | interrupt 유지, 추가 기업/분류 호출 중단 |

기본 최초 1회 + 재시도 2회이며 허용 상한은 추가 0~5회다. 대기는
`min(최대 지연, 초기 지연 × 2^재시도순번)`의 50~100%(equal jitter)다.
기본 초기 1초·최대 10초, 설정 가능 범위는 각각 1ms~30초. 연결/읽기 timeout은 기본 5/30초다.

실패한 현재 HTTP 페이지만 재시도하고 완료된 앞 페이지는 다시 추가하지 않는다.
수집·분류·기업 ZIP 다운로드가 같은 클라이언트 정책을 사용하며 서비스/스케줄러에서 중첩 retry를 하지 않는다.
020/429 뒤 기본 1시간(설정 1분~1일)은 외부 요청 없이 차단한다. 차단 중 재호출로 기간을 연장하지 않는다.
만료는 공급자 한도 해제를 보장하지 않는다. Retry-After 해석·cooldown 영속화는 없고 재시작 시 초기화된다.
공식 코드: [OpenDART 공시검색 가이드](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001).

### 저장과 재시작

1. 짧은 prepare 트랜잭션에서 최초 종료일(initial_sync_through)과 수집 시작/시도 시각을 저장한다.
2. DB 트랜잭션 밖에서 모든 페이지를 조회한다.
3. 완료 단계에서 기업 상태/기준을 재확인하고 신규 공시·성공 조회일·성공 시각을 함께 커밋한다.
4. 실패 시 성공 기준은 전진하지 않는다. 오류 기록 자체가 DB 장애로 실패해도 다음 실행은 기존 DB 기준에서 시작한다.

최초 범위는 고정 종료일 이전 30일, 이후는 `[마지막 성공 조회일 - 3일, 실행 당일]`이다.
중단 기간을 당일 기준 최근 며칠로 축소하지 않는다. 초기 실패 복구 중 INITIAL_DAYS를 줄이지 않는다.
접수일이 최초 기준일 이하인 공시는 늦게 발견해도 initial_import=true다. 재수집은 기존 메타데이터/탐지 시각/초기 구분을 덮어쓰지 않는다.
전체 페이지가 불완전하거나 DB 배치가 실패하면 부분 삽입·완료 기록을 남기지 않는다. rcept_no UNIQUE와 ON CONFLICT를 함께 사용한다.

일반 기업별 실패는 다른 기업을 계속 처리한다. 인증/설정/한도/interrupt는 추가 API 호출을 중단한다.
기본 10분 fixed delay와 COLLECTION_ENABLED 기본 false를 유지한다. 이전 자동 작업이 끝난 뒤 다음 주기까지 기다린다.
collector/pipeline AtomicBoolean은 단일 앱의 중복 수집/후속 처리만 막고, 재시작 후에는 DB 체크포인트를 읽는다.

## 4. 필터·알림 중복 방지·SMTP 복구

### 적격 조건과 작업 생성

관심 등록·모니터링·기업 알림·해당 EMAIL 유형 조건이 모두 활성이고, A~E로 확인된 공시만 검사한다.
initial_import=false이고 collected_at이 기업/조건/수신자의 전역 활성 기준보다 모두 엄격히 뒤여야 한다.
접수일만으로 신규 여부를 판단하지 않는다. 재활성화는 기준을 새로 잡으며 연속 활성 재시작은 기존 기준을 유지한다.
분류 지연은 최초 탐지 시각을 바꾸지 않는다. 유형 조회 실패 시 확인 시각/부분 분류를 커밋하지 않는다.

A~E 검색의 rcept_no/접수일로 기존 행에만 분류를 연결하고 추가 공시를 삽입하지 않는다. 기본 100개/30일 배치,
미분류 재확인은 24시간이다. 확인된 유형은 재사용한다. F~J 추가 조회/임의 E 매핑은 하지 않는다.

알림은 (disclosure_id, channel, 정규화 recipient) UNIQUE, EXISTS 필터, ON CONFLICT로 한 논리 작업만 만든다.
SENT·UNKNOWN·CANCELLED·영구 실패는 반복 수집/분류로 다시 생성하지 않는다. 과거 NULL 수신자 이력도 보수적으로 중복을 막는다.
현재 구현은 단일 이메일 주소이며 local part까지 소문자로 정규화한다.

### 상태 전이와 복구

```text
PENDING → DB 선점 커밋 PROCESSING → SMTP 수락 → 별도 DB 결과 SENT
                                 ├→ 명확한 실패 FAILED → 안전한 경우만 기한/상한 내 재시도
                                 └→ 불확실 UNKNOWN → 자동 재전송 금지
PENDING/재시도 FAILED → 정책 해제 → CANCELLED
```

선점은 FOR UPDATE SKIP LOCKED와 UUID 토큰을 사용한다. 누적 attempt_count와 시각을 커밋한 후 SMTP를 호출한다.
결과는 PROCESSING 상태와 선점 토큰이 같을 때만 반영한다. SMTP 동안 DB 트랜잭션을 유지하지 않으며 공시 저장과도 분리한다.

| 오류/중단 지점 | 처리 |
| --- | --- |
| 명시적 4xx·확실한 연결 성립 실패 | FAILED, 기본 10분 뒤·추가 최대 3회. 총 4회 선점 이내 |
| 인증·5xx·설정/메시지 오류 | 영구 FAILED. 같은 작업 자동 재시도 없음 |
| 일반 timeout/I/O·일부 수락·혼합 예외·예외 그래프 검사 상한 초과 | UNKNOWN. 실제 미수락을 확신할 수 없으므로 자동 재발송 금지 |
| connect timeout을 포함한 MailConnectException | 연결 성립 전 오류로 제한 재시도 가능 |
| 재시도 상한 감소 | 다음 dispatch에서 상한에 도달한 FAILED의 retryable/다음 시각 제거, 원인·횟수·시각 보존 |
| PENDING 커밋 후 중단 | 연속 활성 기준을 유지하면 재시작 후 정상 선점 가능 |
| PROCESSING/SMTP 중단 | 재선점 금지. 기본 10분 후 다음 활성 dispatch에서 UNKNOWN |
| 선점 커밋 응답 유실 | 선점을 확인 못한 호출자는 SMTP를 실행하지 않음. DB PROCESSING이면 이후 격리 |
| SMTP 수락 후 DB 저장 전 종료/저장 실패 | 재발송하지 않음. UNKNOWN 저장을 별도 시도, 이것도 실패하면 PROCESSING을 남겨 격리 |
| SENT 커밋 후 응답 유실 | fallback UNKNOWN 저장은 CAS에서 거절. 실제 DB SENT 보존 |
| 늦은 작업자 결과 | 복구된 UNKNOWN을 덮어쓰지 못함 |

최대 추가 재시도는 0~10회, 간격과 processing timeout은 최소 1분이다. 상한을 나중에 높여도 종료된 작업은 자동 부활하지 않는다.
복구는 전역 활성 dispatch에서 실행한다. 타이머가 꺼져 있으면 명시적 dispatch까지 기다린다. 이 요청 자체가 실제 전송을 일으킬 수 있다.
SENT는 SMTP 수락이지 수신함 전달이 아니다. 결과 응답 통계는 unknown인데 DB는 SENT일 수도 있어 DB 이력이 기준이다.
UNKNOWN 강제 재전송 API는 없다. SMTP와 DB는 원자적이지 않아 exactly-once를 보장하지 않는다.

## 5. 운영 로그·REST·설정

- 수집 로그: runId·watchId/corpCode·조회 범위·시작/종료·fetched/inserted/duplicates·durationMs·안전한 오류 코드.
- 알림 로그: 별도 runId·deliveryId/rceptNo/channel·DB attempt/retryCount·SMTP 결과와 DB 기록 확인 여부·시간·오류.
- INFO는 정상/취소/집계, WARN은 장애/불확실성, ERROR는 예상하지 못한 API 내부 오류다.
- API Key·비밀번호·수신 주소·선점 토큰·원본 예외/SQL/인증 URI는 제외한다. Hibernate 원본 SQL 예외 로깅도 끈다.
- 저장 목록은 GET /api/disclosures. companyId/query/type(A~J) AND 필터, 날짜/접수번호 내림차순, page>=0·size 1~100.
  미분류·과거 공시도 읽을 수 있지만 알림 대상으로 승격하지 않는다. 기업명은 현재 디렉터리 기준, query wildcard 문자는 글자 그대로 검색한다.
- 성공 응답은 기존 DTO/페이지 형식, 오류는 ProblemDetail + code(상위 API 상태가 있으면 upstreamStatus)다.
  등록 201/삭제 204, 입력 400, 경로 404, 메서드 405(Allow 유지), 응답 형식 406, 요청 형식 415,
  데이터 충돌/잠금 409, DB 불가 503, 내부 500, 상위 API 종류에 따라 502/503/504.
- 수집 HTTP 200은 전체 성공이 아니다. 본문 기업별 상태와 체크포인트를 확인한다.
- 수동 POST /api/collections는 수집만 한다. 자동 알림이 켜져 있으면 데이터가 이후 자동 처리 대상이 될 수 있다.
- 모든 실제 API가 OpenAPI에 있으며 필수 DTO·유형·페이지·주요 오류·실제 전송 확인과 UNKNOWN 의미를 문서화했다.
- 기본 COLLECTION_ENABLED / NOTIFICATION_ENABLED / NOTIFICATION_SCHEDULER_ENABLED는 모두 false.
  SMTP 인증/STARTTLS enable/required는 true, 연결/읽기/쓰기 각 5000ms, UTF-8, mail debug/기동 연결 검사는 false다.

환경변수 전체와 실행·SMTP 계정 설정·API 예시는 [README](../README.md)를 기준으로 한다.

## 6. 최종 검증과 A~E 시나리오 근거

실행 명령(외부 API/실제 메일 없음):

```powershell
$env:RUN_LIVE_SMTP_TEST='false'
docker compose config --quiet
docker compose up -d --wait postgres
.\gradlew.bat clean build integrationTest --no-daemon --console=plain
```

| 검증 | 통과 | 실패 | 오류 | 건너뜀 |
| --- | ---: | ---: | ---: | ---: |
| 기본/단위 test | 144 | 0 | 0 | 0 |
| 실제 PostgreSQL integrationTest | 93 | 0 | 0 | 0 |
| 합계 | 237 | 0 | 0 | 0 |

전체 clean 빌드 성공. 기존 236개에 3기업 최종 E2E 한 개만 추가했다. 외부 live/smtp-live 테스트는 제외했으며 건너뜀 수에 포함하지 않는다.
기본 테스트의 폐기용 127.0.0.1 SMTP는 실제 JavaMailSender의 UTF-8·수락·DATA 후 응답 유실을 검증하고 외부로 전달하지 않는다.

| 시나리오 | 이번에 재실행한 근거 |
| --- | --- |
| A 관심 기업 관리 | PhaseFourDIntegrationTests: Mock 3기업 디렉터리 동기화·재사용·기업명/종목코드 검색·등록·목록·모니터링 끄기/켜기 |
| B 공시 수집 | PhaseTwoIntegrationTests, PhaseFourAIntegrationTests: 초기/증분·중복·부분 실패·DB 롤백·기준 복원. 4D: 3기업 3→6공시, 과거 미발송 |
| C 분류/필터 | PhaseThreeAIntegrationTests: A~E/미분류·여러 조건·비활성/재활성·지연 분류. 4D: 3기업별 A/B/C 매칭 |
| D 이메일 | PhaseThreeB/C·PhaseFourB/CIntegrationTests: Mock SMTP 수락·재시도·영구/불확실 실패·중복 방지. 4D: 3작업 SENT 뒤 반복 처리 추가 발송 0 |
| E 장애/재시작 | OpenDartClientTests/OpenDartRetryTests·PhaseFourA/BIntegrationTests: 페이지 실패, API 재시도/억제, prepare 후 중단, PENDING/PROCESSING 복구, 커밋 응답 유실·선점 경쟁 |
| API/Swagger/로그 | DartWatchApplicationTests·PostgresApplicationTests·OperationalLoggingTests: 실제 라우트/OpenAPI·UI HTTP 응답·모델/상태·입력·민감정보 제외 |

4D fixture의 삼성전자·SK하이닉스·NAVER는 요청 시나리오를 위한 Mock 데이터이며 corp_code는 가상 코드다.
이 테스트의 Mock 발송 성공을 실제 해당 기업 공시 수신으로 해석하지 않는다.

Flyway는 새 임시 스키마에 V1~V4를 적용하고 기존 데이터가 있는 상태에서 validate/체크섬을 확인했다.
기존 V2→V3, V3→V4 데이터 보존/이전 테스트도 통과했다. 실제 운영 DB 재기동 실험으로 확대 해석하지 않는다.
실제 PostgreSQL 연결과 실행 중 HTTP 서버의 Health/OpenAPI/Swagger 응답은 PostgresApplicationTests로 확인한다.
개발 DB의 기존 V1~V4 성공 메타데이터와 저장소 마이그레이션 무변경, 종료 후 남은 dw_test_ 스키마 0개를 확인했다.
Compose 설정 검사와 PostgreSQL healthy 상태, .env Git 제외, git diff --check도 확인했다.

### 과거 실제 연동 기록과 이번 실행의 구분

- PHASE_2.md·PHASE_3.md에는 실제 OpenDART liveTest 1개 성공(ZIP·최소 목록·임시 DB 저장) 기록이 있다.
  이번에는 재호출하지 않았다. 실제 유형별 A~E 검색도 이번 실행에서 확인하지 않았다.
- Gmail SMTP Smoke Test 성공은 이전 단계에서 사용자가 제공한 이력이다. 이번에는 재발송하거나 수신함을 확인하지 않았다.
- 이번 일반 테스트에 LiveSmtpSmokeTest/OpenDartLiveTests는 포함하지 않는다.
  실제 SMTP는 별도 smtpSmokeTest + OS RUN_LIVE_SMTP_TEST=true가 필요하며 승인 없이 실행하지 않는다.
  이 테스트가 직접 읽는 MAIL_FROM/NOTIFICATION_EMAIL_TO도 OS 환경변수로 공급해야 한다.

## 7. 알려진 제한과 후속 과제

- 단일 사용자·단일 앱 MVP다. DB 선점 경합 테스트가 다중 인스턴스 전체 파이프라인 보장을 뜻하지 않는다.
- 실제 10분 주기 장시간 운영, OS 강제 종료·실제 네트워크 장애, 최신 외부 서비스의 최종 전달은 미검증이다.
- 3일 중첩보다 늦은 과거 게시, 1000페이지 초과 대량 백필, 깊은 OFFSET/기업명 부분 검색 성능은 보장하지 않는다.
- 분류는 한 배치씩 처리하며 미분류에는 F~J/지연 반영 등이 포함된다. E로 임의 변환하지 않는다.
- cooldown은 재시작하면 초기화된다. SMTP 처리 timeout은 실제 전체 작업보다 충분히 길게 둬야 한다.
- 이미 진행 중인 SMTP와 설정 비활성화를 원자적으로 묶을 수 없다. UNKNOWN은 공급자 기록 확인 후 운영 판단이 필요하다.
- 인증/권한/TLS/백업 자동화/배포 체계는 현재 제공하지 않는다. 공개 노출 전에 별도 준비해야 한다.

향후 후보만 기록하며 이번에 구현하지 않았다.

- Phase 5: NotificationSender를 통한 Kakao/Discord 확장, 채널별 결과·중복·불확실성 정책 유지.
- Phase 6: 인증·React 대시보드·운영 관측성·조회/백필 최적화. AI·주가 분석 등은 별도 범위 결정 필요.
- 운영/배포 준비: 비밀정보 주입·TLS/접근 통제·DB 백업/복원·기본 비활성화·단일 인스턴스 배치·장시간 관측·승인된 실제 3기업 E2E.

Phase 4D에서 개발을 종료했다. Phase 5·6, Commit·Push·배포는 수행하지 않았다.
