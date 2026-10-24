# Phase 2 — OpenDART 연동 및 자동 수집

기존 `company`, `watchlist`, `disclosure` 모델·Repository와 V1 제약조건을 재사용한다.
`opendart`는 외부 통신·파싱, `collection`은 수집 흐름·트랜잭션·스케줄러,
기존 기능 패키지의 Service/Controller는 DB 조회와 관심 기업 API를 담당한다.
새 라이브러리는 추가하지 않았다. JDK ZIP/StAX, 기존 Jackson·Spring RestClient·JPA/JDBC를 사용한다.

## REST API

| 메서드 | 경로 | 요청·결과 |
| --- | --- | --- |
| POST | `/api/companies/sync` | 최초 전체 기업 동기화. 완료 표시가 있으면 다운로드 생략 |
| POST | `/api/companies/sync?refresh=true` | 명시적 기업 정보 재동기화·갱신 |
| GET | `/api/companies?query=005930&page=0&size=20` | 기업명 부분 검색 또는 종목코드 일치 검색, DB 조회만 수행 |
| POST | `/api/watchlist` | `{"companyId":1}`, 201 및 관심 기업 DTO |
| GET | `/api/watchlist` | 관심 기업·모니터링 상태·최근 수집 결과 목록 |
| PATCH | `/api/watchlist/{id}/monitoring` | `{"enabled":false}` 또는 `true` |
| DELETE | `/api/watchlist/{id}` | 관심 등록·관련 조건 삭제, 204. 기업·공시·발송 이력 보존 |
| POST | `/api/collections` | 모든 활성 관심 기업의 수동 수집, 자동 수집과 같은 서비스 사용 |

기업 검색 응답은 `{items, page, size, totalElements}`, 관심 목록은 DTO 배열이다.
DB 식별자 `id`, OpenDART `corpCode`, 종목코드 `stockCode`를 구분한다.
검색은 필수 검색어·길이·페이지 범위·최대 페이지 크기 100을 검증한다.
존재하지 않는 기업/관심 등록은 404, 중복 관심 등록·동시 작업은 409, 잘못된 입력은 400이다.
기존 Health의 단순 JSON 응답을 유지하고 신규 API도 별도 성공 응답 wrapper를 도입하지 않았다.
오류는 Spring `ProblemDetail`과 안전한 `code`를 반환하며 SQL·인증키·원본 예외를 응답하지 않는다.

수집 API의 HTTP 200만으로 수집 성공을 판단하지 않는다.
본문 전체 상태는 `COMPLETED`, `PARTIAL_FAILED`, `FAILED`이며 기업별로 `SUCCESS`, `FAILED`, `SKIPPED`를 반환한다.
각 결과의 조회 기간·조회 건수·신규 저장 건수·안전한 오류 코드를 확인한다.
인증·설정·한도 오류 시 같은 실행의 남은 기업 호출을 중단한다.

## 기업 고유번호 동기화

`corpCode.xml`은 한 번 다운로드한 뒤 DB에 보관하며 검색·관심 등록 요청에서는 재다운로드하지 않는다.
`company_directory_sync`의 완료 표시로 초기 동기화 여부를 판별한다. 단순 기업 행 존재 여부를 기준으로 삼지 않으므로
일부 기업만 미리 입력된 DB도 전체 동기화할 수 있다.
다운로드·전체 파싱 후 한 DB 트랜잭션에서 `corp_code` 기준 upsert와 완료 표시를 함께 커밋한다.
실패하면 부분 저장·완료 표시를 남기지 않는다. 기존 PK·관심 등록·공시 FK는 유지한다.
재동기화 시 입력된 기업들의 종목코드 매핑을 먼저 비운 뒤 할당하여 코드 재배정의 XML 순서 의존성을 없앴다.
응답에 없는 기업은 삭제하지 않는다. 기업명·종목코드·최종 수정일을 갱신하고 선행 0과 nullable 종목코드를 유지한다.

압축 응답은 최대 32 MiB, 해제 XML은 최대 64 MiB, 기업 레코드는 최대 500,000개로 제한한다.
파일을 디스크에 쓰지 않으며 단일 루트 `CORPCODE.xml`만 허용한다. ZIP 경로·추가 엔트리·손상·잘못된 XML을 거부한다.
StAX의 DTD·외부 엔티티·외부 리소스 해석을 차단한다.
ZIP 대신 반환된 오류 XML의 `status`·`message`도 검사한다. `013`의 빈 기업 파일은 성공 동기화로 기록하지 않는다.

## 공시 수집과 기준일

관심 등록과 모니터링이 모두 활성인 기업만 조회한다. 등록/활성 변경 시 자동으로 즉시 API를 호출하지 않는다.
자동 실행을 기다리거나 수동 수집 API를 사용한다.

1. 기업별 짧은 트랜잭션에서 최초 기준일과 시도 시각을 저장한다.
2. DB 트랜잭션 밖에서 해당 기간의 모든 페이지를 조회한다.
3. 다시 관심 등록·활성 상태·기준일을 확인한 뒤, 한 트랜잭션에서 신규 공시와 마지막 성공 조회일을 함께 저장한다.
4. API 또는 저장 실패 시 성공 기준일은 유지하고 안전한 오류 코드만 기록한다.

최초 시도 당일(Asia/Seoul)을 `initial_sync_through`로 **고정**한다.
기본 첫 조회 범위는 `[기준일 - 29일, 기준일]`이며 실패 후 날짜가 바뀌어도 같은 종료일로 재시도한다.
실패 복구 중 초기 조회 범위 설정을 줄이지 않는 것이 좋다.
초기 조회가 완료된 다음 실행부터 `[마지막 성공 조회일 - 3일, 실행 당일]`을 조회한다.
일 단위 양 끝을 포함하므로 마지막 성공일과 그 이전 3일을 다시 조회한다.
중단 기간이 길어도 마지막 성공일에서 다시 시작하므로 고정된 최근 며칠만 조회해 중간 구간을 건너뛰지 않는다.

공시 접수일이 최초 기준일 **이하**면 늦게 발견되더라도 `initial_import=true`, 이후면 `false`로 저장한다.
OpenDART 접수일에는 시각이 없어 최초 당일 뒤늦게 제출된 공시도 과거 공시로 취급하는 보수적 기준이다.
이미 저장한 공시의 최초 수집 구분과 메타데이터는 재수집 때 덮어쓰지 않는다.
관심 등록 삭제 후 재등록은 새로운 최초 기준일을 갖지만 기존 접수번호와 발송 이력은 유지된다.

클라이언트는 `corp_code`, `bgn_de`, `end_de`, `page_no`, `page_count`, `last_reprt_at=N`을 명시한다.
접수일 오름차순으로 요청하며 모든 페이지의 총 건수·페이지 수·페이지 번호·기업 코드·접수일 범위와 전체 건수를 확인한다.
페이지 중간 실패·메타데이터 변동·중복 페이지·용량 초과를 성공으로 반환하지 않는다.
호출 중 원본 목록이 바뀌어 응답이 일관되지 않으면 다음 실행에서 같은 기준점부터 다시 조회한다.
기업당 최대 기본 1,000페이지(페이지당 최대 100건)를 넘으면 실패로 기록하고 기준일을 전진시키지 않는다.

기존 접수번호를 묶어서 조회한 뒤 신규 항목만 저장하며, 동시 삽입은 PostgreSQL `ON CONFLICT (rcept_no) DO NOTHING`으로 처리한다.
같은 접수번호를 반복 수집해도 행이 늘지 않는다. 다른 접수번호의 정정공시는 별도 행으로 남는다.
수집 결과는 관심 기업 행의 시도/성공 시각·성공 조회일·조회/신규 건수·최근 오류 및 애플리케이션 로그에 기록한다.
별도의 실행별 감사 이력 테이블은 추가하지 않았다. 신규 라이브러리·복잡한 retry/분산 worker도 추가하지 않았다.

## 설정

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| `OPENDART_API_KEY` | 빈 값 | 호출 시 필수, 키 없이 DB API·서버 기동 가능 |
| `OPENDART_CONNECT_TIMEOUT` | `5s` | 연결 타임아웃 |
| `OPENDART_READ_TIMEOUT` | `30s` | 응답 타임아웃 |
| `OPENDART_PAGE_SIZE` | `100` | 1~100 |
| `OPENDART_MAX_PAGES` | `1000` | 1~1000, 기업별 1회 수집 상한 |
| `COLLECTION_ENABLED` | `false` | 자동 수집 활성 여부 |
| `COLLECTION_INTERVAL` | `PT10M` | 앞 작업 종료 후 다음 실행까지 fixed delay |
| `COLLECTION_INITIAL_DELAY` | `PT30S` | 앱 기동 후 최초 실행 지연 |
| `COLLECTION_INITIAL_DAYS` | `30` | 최초 조회 기간, 1~365일 |
| `COLLECTION_OVERLAP_DAYS` | `3` | 마지막 성공일 이전 재조회 일수, 1~30일 |

설정 변경 후 앱을 재시작한다. 수동 수집은 스케줄러 활성 여부와 독립적이다.
자동·수동 수집은 동일 서비스의 AtomicBoolean guard를 공유하여 단일 앱 내 중복 실행을 차단한다.
관심 상태 변경·수집 상태 커밋에는 DB 행 잠금을 사용한다. 여러 앱 인스턴스 전체에 걸친 분산 실행 잠금은 구현하지 않았다.
MVP는 앱 인스턴스 하나로 운영한다.

## 오류와 인증키 보호

본문 `000`은 정상, `013`은 정상 빈 공시 목록, `010/011/012/901`은 인증·접근 오류,
`020`은 한도, `800/900`은 시스템 오류로 분류한다. 기타 코드도 오류로 처리한다.
네트워크 연결 오류, 타임아웃, HTTP 오류, 잘못된 응답은 각각 별도 오류 종류다.
`message` 존재 여부도 검사하지만 외부 메시지·원본 예외·인증된 요청 URL을 로그/응답에 포함하지 않는다.
HTTP 오류의 본문도 오류 메시지에 붙이지 않는다.
RestClient 요청 로그를 끄고 인증키를 가진 설정 객체에 값이 포함되는 `toString()`도 만들지 않았다.
외부 API 인증은 쿼리 파라미터 방식이므로 HTTP wire logging이나 전체 요청 URI 추적을 임의로 켜지 않는다.

## V2 스키마 변경

- `disclosure`: nullable `company_name`, `filer_name`, `remarks` 추가. 기존 데이터의 추정 backfill은 하지 않는다.
- `watched_company`: 최초 기준일, 마지막 성공 조회일, 시도/성공 시각, 최근 오류, 조회/신규 건수 추가.
- `company_directory_sync`: 단일 완료 표시·완료 시각·기업 수 메타데이터. 새 비즈니스 Entity는 추가하지 않았다.
- V1 SQL과 기존 중복 제약조건은 유지한다. Flyway가 V2를 적용하고 JPA는 계속 `validate`한다.

## Phase 3 전에 결정할 사항

목록 응답은 대분류 `pblntf_ty`와 정정 원본 접수번호를 제공하지 않는다.
현재 `disclosure_type`은 NULL로 저장하며 보고서명·비고로 임의 분류하거나 정정 원본을 연결하지 않는다.
Phase 3에서는 A~J 유형별 검색 파라미터로 각각 조회한 결과에 명시적 유형을 부여하는 등의 수집 방법과 호출량을 결정해야 한다.
과거 공시 제외는 `initial_import`를 사용하되 기존 Phase 2 데이터의 알림 처리 시작 기준도 함께 정해야 한다.
이번 작업에는 조건 매칭·발송 작업 생성·NotificationSender·이메일·인증·프론트엔드를 추가하지 않았다.
복잡한 재시도, 장기간 지연 게시(중첩 구간보다 늦은 공시), 다중 인스턴스 잠금, 대규모 백필은 Phase 4 검토 대상이다.

공식 근거: [공시검색](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001),
[기업 고유번호](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019018).

## 검증 명령

```powershell
# 실제 OpenDART 호출 없음
.\gradlew.bat clean build integrationTest

# 명시적 실제 호출: ZIP 1회, 최대 1페이지의 7일 공시, 임시 PostgreSQL 스키마 저장·정리
.\gradlew.bat liveTest
```

실제 테스트는 키가 설정되어 있고 호출이 허용된 환경에서만 실행한다.
기본 `build`와 `integrationTest`는 live 태그를 제외하고 스케줄러를 비활성화한다.
키가 없거나 API가 실패한 live 검증을 성공/생략으로 자동 처리하지 않는다.
테스트 보고서는 `build/reports/tests/{test,integrationTest,liveTest}/index.html`에 생성된다.

## 실행 결과 (2026-10-24)

- Java 21에서 최종 `build integrationTest` 성공. 기본/단위 51개, PostgreSQL 통합 37개, 실패·오류·생략 0개.
- `liveTest` 1개 성공. 실제 기업 고유번호 ZIP 파싱, 한 기업의 7일 공시 조회, 임시 PostgreSQL 스키마 저장과 최초 수집 구분을 확인했다.
  ZIP 다운로드 1회와 공시 목록 요청 1회로 검증했으며 실제 API를 반복 재수집하지 않았다.
- 설정된 짧은 주기에서 `@Scheduled`의 반복 실행을 확인했다. 외부 호출은 Mock 서비스로 대체했다.
- V1→V2 개발 DB 마이그레이션 성공과 JPA `validate`를 확인했다.
- 소스·문서·예제·테스트 XML 출력에서 설정된 인증키가 노출되지 않았음을 확인했다.
- 미검증: 다중 앱 인스턴스 운영, 장시간 장애 복구, 실제 10분 주기의 장시간 운영. 해당 범위의 성공을 주장하지 않는다.
