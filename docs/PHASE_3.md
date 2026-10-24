# Phase 3 — 분류·이메일·자동 파이프라인

3A·3B·3C를 구현했다. **아래는 3A 구현 시점의 설계 기록**이며,
메일·이력·활성화 기준은 [Phase 3B](PHASE_3B.md), 자동 연결·타이머 설정은 [Phase 3C](PHASE_3C.md)를 기준으로 한다.

- **3A 완료 범위:** 공식 A~E 분류, 조건 등록·수정·조회·비활성화, 기업별 알림 사용 여부, 발송 없는 후보 조회.
- **3B 완료 범위:** Mail Starter, NotificationSender, SMTP, 수신자·발송 상태 확장, 전송·재시도·중복 처리. 상세 내용은 별도 가이드에 기록한다.
- **3C 완료 범위:** 수집→분류→알림 작업 생성과 독립 전송 타이머 연결. 상세 내용은 별도 가이드에 기록한다.

기존 `DisclosureCollectionService`, 수집 스케줄러, `POST /api/collections`는 유지한다.
이번에는 분류를 수집 작업에 자동 연결하지 않는다. 실제 이메일과 알림 작업은 생성하지 않으며
기존 `NotificationDelivery` 및 상태 enum도 변경하지 않았다. 새 라이브러리와 비즈니스 Entity는 추가하지 않았다.

## 유형 분류와 API 호출 전략

기존 `DisclosureType`과 `OpenDartClient`를 재사용한다. 클라이언트에 유형 인자를 받는 오버로드를 추가했다.
기존 유형 없는 조회 동작은 그대로다. 유형별 조회도 동일 인증·타임아웃·HTTP/본문 상태·페이지네이션 검증을 사용하며
`last_reprt_at=N`을 유지한다.

| API 코드 | 저장 enum | MVP 조건 선택 |
| --- | --- | --- |
| A | PERIODIC | 가능 |
| B | MATERIAL_EVENT | 가능 |
| C | ISSUANCE | 가능 |
| D | SHAREHOLDING | 가능 |
| E | OTHER | 가능 |
| F~J | 기존 별도 enum 유지 | 불가, E로 변환하지 않음 |

`POST /api/watchlist/{id}/disclosure-classifications`는 활성 모니터링 관심 기업 한 곳의 DB 공시를 분류한다.
보고서명·비고로 유형이나 정정 원본을 추론하지 않는다.

1. `disclosure_type IS NULL`인 행만 선택한다. 분류가 확인된 행은 재조회하지 않는다.
2. 한 번도 확인하지 않은 공시를 먼저, 그 안에서는 접수일·PK 순으로 선택한다.
   미분류 재확인은 마지막 확인 후 기본 24시간이 지나야 가능하다.
3. 기본 최대 100개, 최대 30일 범위의 접수일을 한 배치로 잡는다. 첫 대상 날짜부터 범위를 제한한다.
   응답의 `checked`는 이번에 처리한 수이며 전체 잔여 작업 수가 아니다. 백필이 남으면 다시 요청한다.
4. A→E 순서로 유형별 `list.json` 검색을 하고 해당 범위의 **모든 페이지**를 확인한다.
   후보 전체를 찾으면 남은 유형 조회는 생략한다. `queriedTypes`는 유형 검색 횟수로, 실제 HTTP 요청 수는 페이지 수에 따라 더 많을 수 있다.
5. `rcept_no`와 접수일이 일치하는 기존 행에만 유형을 연결한다. 조회 결과의 추가 공시를 삽입하지 않으며
   별도 접수번호의 정정공시는 별도 행으로 유지한다.
6. A~E에서 확인되지 않은 공시는 NULL로 유지하고 확인 시각만 기록한다.
   이는 F~J, 아직 검색에 반영되지 않은 공시 등을 구별하지 않는 **미분류** 상태이며 E가 아니다.
   MVP에서는 호출 비용을 줄이기 위해 F~J 확인용 추가 검색을 하지 않는다.
7. 필요한 조회가 모두 끝난 후에만 유형과 확인 시각을 한 트랜잭션으로 저장한다.
   중간 페이지·유형 조회 실패나 모순된 유형 응답에서는 부분 분류·확인 시각을 커밋하지 않는다.

분류 완료 데이터와 미분류 재확인 시각은 재시작 후에도 DB에서 재사용한다.
매 수집 주기마다 5종 검색을 실행하지 않으며, 대상이 없으면 API 호출은 0회다.
분류 API 자체에는 단일 앱 내 중복 실행 guard가 있다. 기존 수집과 같이 여러 인스턴스 전체의 분산 실행 잠금은 범위 밖이다.
호출 중 관심 기업이 삭제되거나 모니터링이 꺼지면 저장 단계에서 충돌로 처리한다.

공식 근거: [OpenDART 공시검색 가이드](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001).
분류 결과는 `pblntf_ty` 요청 조건과 응답 접수번호에 근거하며 원본 관계를 임의 생성하지 않는다.

## REST API

| 메서드 | 경로 | 입력 / 동작 |
| --- | --- | --- |
| POST | `/api/watchlist/{id}/disclosure-classifications` | 미분류 공시 한 배치 확인, `{checked, classified, unclassified, queriedTypes}` |
| GET | `/api/watchlist/{id}/notification-rules` | 기업별 사용 여부·활성 기준·조건 목록 |
| PUT | `/api/watchlist/{id}/notification-rules/{type}` | A~E 코드, `{"enabled":true}` 또는 `false`, 동일 조건 upsert |
| PATCH | `/api/watchlist/{id}/notifications` | `{"enabled":true}` 또는 `false`, 기업별 후보 필터 사용 여부 |
| GET | `/api/watchlist/{id}/notification-candidates?page=0&size=20` | 발송 없는 후보 미리보기, `{items,page,size,totalElements}` |

조건은 기존 `(watched_company_id, disclosure_type, channel)` UNIQUE 제약조건을 그대로 사용하며 현재 채널은 EMAIL이다.
PUT은 조건의 등록·수정·비활성화를 모두 처리한다. 유형 선택을 바꾸려면 기존 유형을 끄고 새 유형을 켠다.
F~J, enum 이름, 소문자 코드, enabled 누락은 400이다. 없는 관심 기업은 404, 비활성 모니터링의 분류 요청은 409다.
기존 `ProblemDetail` 오류 응답을 재사용한다. 페이지 크기는 1~100이다.

기본 기업별 알림 사용 여부는 **false**다. 조건이 없거나 꺼져 있으면 후보도 없다.
이 사용 여부는 3A의 후보 필터 설정이며 SMTP 발송을 허가하거나 실행하는 설정이 아니다.
후보 조회는 읽기 전용으로, 반복 조회해도 알림 작업·메일·발송 이력이 생기지 않는다.

## 알림 후보 시작 기준

후보는 다음을 모두 만족해야 한다.

- 해당 기업이 관심 기업으로 남아 있고 모니터링과 기업별 알림 설정이 활성이다.
- A~E의 확인된 유형과 현재 EMAIL 조건이 일치하고 조건도 활성이다.
- `initial_import=false`다.
- **DB 최초 저장 시각인 기존 `collected_at`**이 기업별 알림과 해당 조건의 활성 기준 시각보다 모두 뒤다.

단순 접수일 비교를 사용하지 않는다. Phase 2 데이터는 최초 탐지 시각을 기존 `collected_at`으로 해석한다.
분류는 이 시각이나 `initial_import`를 바꾸지 않으므로 과거 공시를 새 데이터로 승격시키지 않는다.
유형을 늦게 확인하더라도 설정이 계속 활성 상태였다면 기존 탐지 시각을 기준으로 후보에 포함된다.
미분류 상태에서는 후보에 포함하지 않는다.

처음 알림/조건을 켜거나 껐다가 다시 켜면 해당 활성 기준 시각을 새로 기록한다.
켜진 상태에서 다시 true로 설정하는 것은 기준 시각을 바꾸지 않는다.
모니터링을 껐다가 다시 켜면 기업별 알림 기준도 다시 잡아 비활성 기간 데이터가 일괄 후보가 되지 않게 한다.
경계 시각과 같은 시각의 탐지는 보수적으로 제외한다(`>` 비교).
기업·유형·활성 조건은 EXISTS 쿼리로 확인하므로 조건 조인으로 같은 공시가 중복 행이 되지 않는다.

이는 **후보 필터**이며 발송 중복 방지나 발송 예약을 구현한 것은 아니다.
3B에서는 수신자·채널별 UNIQUE, DB claim, UNKNOWN 처리 및 실제 발송 시작 기준을 별도로 구현해야 한다.
특히 3B를 도입할 때 3A 후보를 과거 발송 대기열로 그대로 전환하지 않도록 전역 발송 시작 정책이 필요하다.

## V3 마이그레이션

`V3__add_classification_and_filter_activation.sql`만 추가했으며 V1·V2는 수정하지 않았다.

- `disclosure.classification_checked_at` 및 미분류 기업별 조회용 partial index 추가.
- `watched_company.notifications_enabled` 기본 false, `notifications_enabled_since` 추가.
- `notification_rule.enabled_since` 추가.
- 기존 공시·기업·조건·발송 이력은 삭제하거나 재생성하지 않는다.
- 기존 조건의 enabled 값은 보존하고 활성 기준은 NULL로 둔다. 기업별 설정을 켜고 해당 조건도 API에서
  명시적으로 활성화해야 필터 대상이 된다. 과거 활성 시각을 추정해 backfill하지 않는다.

## 설정

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| `CLASSIFICATION_BATCH_SIZE` | `100` | 1~1000, 한 번에 분류할 DB 공시 수 |
| `CLASSIFICATION_WINDOW_DAYS` | `30` | 1~90일, 한 배치의 접수일 범위 |
| `CLASSIFICATION_RETRY_INTERVAL` | `PT24H` | 미분류 재확인 대기, 최소 1분 |

기존 OpenDART 인증키·페이지 크기·최대 페이지·타임아웃 설정을 그대로 쓴다.
SMTP 환경변수나 Mail Starter는 이번 단계에 추가하지 않았다.

## 사용 순서

1. 기존 수집 API로 공시를 저장한다.
2. 분류 API를 호출한다. 기존 Phase 2 공시도 분류할 수 있다.
3. 원하는 A~E 조건을 PUT하고 기업별 알림 설정을 켠다.
4. 이후 새로 저장된 공시를 분류하고 후보 조회 API로 확인한다.

처음 설정 전에 저장된 공시는 분류되어도 후보가 되지 않는다.
자동 수집 후 자동 분류·작업 생성·발송은 3C 구현 전까지 연결되지 않는다.

## 검증

```powershell
.\gradlew.bat build integrationTest
# 기존 Phase 2 실제 OpenDART 회귀 검증(명시적 실행, 이메일 없음)
.\gradlew.bat liveTest
```

기본 테스트는 외부 API를 Mock한다. PostgreSQL 테스트는 소유한 임시 스키마에서 실행하고 정리한다.
추가 검증 범위는 A~E 요청·분류, 모든 페이지, 미분류 및 결과 캐시, 실패 시 원자성,
기존 데이터 마이그레이션, 조건 API·중복 upsert, 활성 기준·지연 분류·페이지네이션이다.
실제 유형별 API 검증과 SMTP 검증은 별개이며 SMTP는 이번 범위에 없다.

실행 결과 (2026-10-24):

- Java 21 `build integrationTest` 성공: 기본/단위 66개, PostgreSQL 통합 50개, 실패·오류·생략 0개.
- 기존 `liveTest` 1개 성공: Phase 2 ZIP·공시 목록·임시 DB 저장 회귀 검증. 기존 89개를 포함해 총 117개 통과.
- 신규 유형별 조회는 Mock HTTP와 Mock OpenDART 기반 PostgreSQL 통합 테스트로 검증했다. 실제 유형별 조회는 실행하지 않았다.
- V1·V2 파일 무변경, 기존 행이 있는 V2→V3 마이그레이션 및 개발 DB V3 적용 성공을 확인했다.
- 모든 테스트 종료 후 `dw_test_` 스키마 0개를 확인했다. 이메일은 발송하지 않았다.
- 3B·3C·Phase 4, Commit·Push·배포는 진행하지 않았다.
