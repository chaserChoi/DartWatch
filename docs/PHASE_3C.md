# Phase 3C — 자동 파이프라인·처리 스케줄러

기존 수집·분류·조건·작업 생성·발송 서비스를 연결한다.
새 의존성, Entity, DB 테이블, 마이그레이션, REST API는 추가하지 않는다. V1~V4와 기존 수동 API를 유지한다.
운영 경보·복잡한 장애 복구 등의 Phase 4 기능은 추가하지 않는다.

## 자동 실행 흐름

```text
수집 타이머 (COLLECTION_ENABLED, 기본 10분 fixed delay)
    → 기존 collectAll(): 활성 관심 기업 조회·전체 페이지 수집·저장
    → 수집 SUCCESS 기업만 기존 classify(): 미분류 한 배치 확인
    → 분류 성공 + NOTIFICATION_ENABLED=true이면 기존 enqueue(): 적격 작업 한 배치 저장

전송 타이머 (NOTIFICATION_ENABLED + NOTIFICATION_SCHEDULER_ENABLED, 기본 1분 fixed delay)
    → 기존 dispatch(): 오래된 PROCESSING을 UNKNOWN으로 격리
    → 현재 정책과 기한 확인·DB 선점
    → SMTP → 별도 트랜잭션으로 결과 저장
```

`CollectionPipelineService`는 바깥 DB 트랜잭션을 만들지 않는다.
수집·분류·작업 생성은 기존 서비스의 별도 트랜잭션에서 커밋한다.
파이프라인은 SMTP를 직접 호출하지 않고 PENDING 작업까지만 생성한다.
전송 타이머는 해당 작업과 기한이 된 재시도 작업을 독립적으로 처리한다.

기존 수집 타이머가 파이프라인을 호출하므로 새로운 중복 수집 타이머는 없다.
한 앱 안에서 파이프라인 guard가 수집부터 후속 작업까지 겹치는 실행을 막는다.
개별 수집·분류 서비스의 기존 guard와 전송 작업의 DB 선점·토큰 검사도 유지한다.

## 실패 격리와 복구

- 수집 FAILED/SKIPPED 기업은 그 실행에서 분류·작업 생성을 수행하지 않는다.
- 분류 실패 시 해당 기업의 작업 생성을 건너뛰며 이미 저장한 공시·수집 성공 기준은 유지한다.
- 작업 저장 실패도 수집·분류 결과를 롤백하지 않는다.
- 다음 수집 성공 시 새 공시 0건이어도 미완료 분류와 적격 작업 생성을 다시 확인한다.
  이미 저장한 접수번호·유형·작업은 재사용하므로 중복 저장·작업 생성 없이 복구할 수 있다.
- 설정·인증·호출 한도 오류가 확인되면 같은 파이프라인 실행에서 남은 유형 API 호출을 중단한다.
  일반 기업별 API 오류는 다른 수집 성공 기업의 후속 처리를 막지 않는다.
- SMTP 실패는 별도 전송 작업에서 처리하므로 공시·수집 기준을 롤백하지 않는다.
- 전송 타이머는 재시도에 OpenDART를 호출하지 않는다. 3B의 최대 횟수·간격·UNKNOWN 제외를 그대로 따른다.
- 새 수집이 실패하더라도 이전 성공 실행에서 이미 생성된 적격 작업은 독립 전송 타이머에서 처리할 수 있다.
  현재 실패 실행이 새 알림 작업을 만드는 것은 아니다.

분류는 기존 배치 크기·날짜 범위·재확인 대기 시간을 사용한다.
모든 공시를 한 실행에서 끝없이 재분류하거나 모든 작업을 무제한 생성하지 않는다.
대상·배치가 남으면 다음 수집 성공 주기에 이어서 처리한다.
F~J를 E로 간주하지 않으며, 최초 수집·활성화 이전·비활성 기간 제외 기준도 변경하지 않았다.

로그는 수집/후속 단계별 상태, 안전한 오류 코드, 기업 등록 ID, 집계 수를 기록한다.
분류·작업 저장 실패를 수집 실패로 덮어쓰지 않고 파이프라인의 PARTIAL_FAILED로 구분한다.
수집 자체가 전부 실패한 경우에는 FAILED다. 원본 예외·인증키·SMTP 비밀번호를 출력하지 않는다.

## 실행 순서와 스케줄러 독립성

메일 전역 활성 기준을 저장하는 기존 ApplicationReady 리스너를 먼저 실행한 후 타이머 작업을 허용한다.
준비 완료 전 도착한 타이머 tick은 아무 작업 없이 건너뛰며 다음 fixed delay tick에서 다시 확인한다.
따라서 설정된 초기 지연을 아주 짧게 잡아도 활성 기준이 준비되기 전에 공시/메일 처리를 시작하지 않는다.

공통 `ThreadPoolTaskScheduler`의 실행 스레드는 2개다.
한 수집 작업이 OpenDART를 기다리는 동안 전송/재시도 타이머가 계속 실행될 수 있다.
각 타이머는 fixed delay로, 같은 타이머의 작업 종료 후 설정 간격을 기다린다.
SMTP 처리 시간과 polling 간격에 따라 재시도는 설정 기한 **이후 다음 tick**에서 실행하며 정시 실행을 보장하지 않는다.

다중 앱 인스턴스의 전체 수집·분류 분산 잠금은 추가하지 않았다. 단일 인스턴스 MVP가 기준이다.
발송 작업 자체의 중복 선점은 기존 DB 잠금·토큰·UNIQUE로 보호한다.
SMTP와 DB 사이의 exactly-once 보장은 주장하지 않는다.

## 설정과 기본값

| 환경변수 | 기본값 | 동작 |
| --- | --- | --- |
| `COLLECTION_ENABLED` | `false` | 수집→분류 파이프라인 타이머 활성화 |
| `COLLECTION_INTERVAL` | `PT10M` | 파이프라인 fixed delay |
| `COLLECTION_INITIAL_DELAY` | `PT30S` | 파이프라인 초기 지연 |
| `NOTIFICATION_ENABLED` | `false` | 기존 작업 생성·메일 전송 전역 사용 여부 |
| `NOTIFICATION_SCHEDULER_ENABLED` | `false` | 새 전송/재시도 타이머 활성화 |
| `NOTIFICATION_SCHEDULER_INTERVAL` | `PT1M` | 전송 처리 fixed delay |
| `NOTIFICATION_SCHEDULER_INITIAL_DELAY` | `PT30S` | 전송 처리 초기 지연 |

설정 변경 후 앱을 재시작한다.

- 수집만 켜면 수집과 분류가 자동으로 실행된다. 메일 전역 설정이 꺼져 있으면 작업 생성·SMTP 호출은 없다.
- 메일 전역 설정을 켜고 수집을 켜면 적격 작업을 자동 생성한다. 전송 타이머가 꺼져 있으면 명시적 dispatch 전까지 대기한다.
- 자동 전송은 메일 전역 설정과 전송 타이머 설정을 **둘 다 true**로 해야 한다.
- 수집을 꺼도 전송 타이머만 켜서 기존 대기·재시도 작업을 처리할 수 있다.
- 실제 SMTP 활성화·전송은 사용자 승인 후에 진행한다. 이번 검증에서는 외부 이메일을 보내지 않는다.

완전 자동 실행에는 세 활성화 플래그와 유효한 OpenDART·SMTP 설정, 관심 기업·유형 조건 설정이 필요하다.
기존 시작 기준은 보존되므로 플래그를 켰다고 이미 수집한 과거 공시가 일괄 발송되지 않는다.
기본값을 모두 유지하면 외부 수집·자동 메일은 실행되지 않는다.

## 수동 API와 호환성

`POST /api/collections`는 계속 **수집 전용**이다. 이 요청 자체는 분류·작업 생성·SMTP를 호출하지 않는다.
기존 분류·작업 생성 API와 `confirmSend=true`가 필요한 명시적 dispatch API도 유지한다.

자동 메일을 명시적으로 켠 환경에서는 수동 수집으로 새로 저장된 공시도 이후 자동 주기의 적격성 검사 대상이 될 수 있다.
단순 검증 환경에서는 `NOTIFICATION_ENABLED=false`, `NOTIFICATION_SCHEDULER_ENABLED=false`를 유지한다.
후보 미리보기·이력 조회 API 자체는 계속 전송하지 않는다.

## 주요 파일

- `collection/CollectionPipelineService`: 성공 기업 후속 처리·실패 격리·전체 실행 guard.
- `collection/CollectionScheduler`: 기존 타이머를 파이프라인에 연결.
- `notification/NotificationScheduler`, `NotificationSchedulingConfiguration`: 별도 전송 타이머와 이중 활성화 조건.
- `scheduling/SchedulingConfiguration`, `SchedulerReadiness`: 공통 스레드 풀과 준비 완료 후 실행.
- `notification/NotificationDispatchService`: 활성 기준 리스너 실행 순서만 명시하고 기존 DB 선점·전송 로직 유지.
- `application.yaml`, `.env.example`, Gradle 테스트 기본 설정 및 관련 테스트.

## 검증

```powershell
.\gradlew.bat build integrationTest
# 기존 OpenDART 실제 연동 회귀 검증만 명시적으로 실행
.\gradlew.bat liveTest
```

일반 Spring Boot 테스트에서는 두 타이머와 메일 전역 사용을 강제로 끈다.
타이머 테스트는 ApplicationContextRunner와 Mock 서비스만 사용해 짧은 간격의 실제 반복 실행을 검증한다.
준비 완료 전 tick 무시, 이중 활성화, 수집 없이 전송 타이머 실행, 수집이 막힌 동안 전송 tick 진행도 확인한다.

PostgreSQL 통합 테스트는 실제 수집·분류·조건·작업 생성·선점·이력 서비스를 사용하고 OpenDART·NotificationSender만 Mock한다.
최초/증분 구분, 전체 연결과 중복 방지, 분류·작업 저장 실패 후 복구, SMTP 실패 후 독립 재시도,
UNKNOWN 복구·기본 비활성화·수동 수집 전용 동작을 확인한다.
이전 단계의 폐기용 루프백 SMTP 및 상태/제약조건 회귀 테스트도 유지한다.

실제 유형별 API와 외부 SMTP, 장시간 주기 운영·다중 인스턴스 전체 파이프라인은 별도 검증 범위다.
Phase 4, Commit·Push·배포는 진행하지 않는다.

실행 결과 (2026-10-24):

- Java 21 `build integrationTest` 성공: 기본/단위 89개, PostgreSQL 통합 71개, 실패·오류·생략 0개.
- 타이머 독립성 검증을 강화한 후 `test liveTest`도 성공. 기존 실제 OpenDART 회귀 1개를 포함해 총 161개 통과.
- 실제 외부 호출은 기존 ZIP·공시 목록의 최소 회귀 검증뿐이다. 신규 파이프라인의 OpenDART와 NotificationSender는 Mock으로 검증했다.
- 짧은 주기의 실제 타이머 반복 실행과 수집 작업이 차단된 동안의 새 전송 tick 실행을 확인했다.
- 기존 V1~V4 파일과 DB 스키마를 유지했으며 테스트 종료 후 `dw_test_` 스키마 0개를 확인했다.
- 기존 루프백 SMTP 테스트도 통과했지만 외부 SMTP·실제 수신자에게 이메일은 발송하지 않았다.
- 장시간 운영, 실제 SMTP를 포함한 자동 파이프라인, 다중 인스턴스 전체 수집 잠금은 미검증이다.
