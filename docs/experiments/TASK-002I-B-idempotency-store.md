# TASK-002I-B — 선점 요청 멱등키 운영 저장소

> 관련: [ADR-009](../adr/ADR-009-idempotency-key.md) (C1·C2 채택, C3·C4 미결정), [IDEMPOTENCY_CONTRACT.md](../IDEMPOTENCY_CONTRACT.md),
> [INVARIANTS.md](../INVARIANTS.md) I-18, CLAUDE.md 규칙 7·14~17,
> [TASK-002I-A](TASK-002I-A-hold-idempotency-contract.md) — 계약과 장애 구간 실험(테스트 전용 구현)

## 1. 목적과 범위

Task 2I-A 에서 테스트 전용 테이블·구현으로 검증한 계약을 **운영 migration 과 JDBC 저장소**로 옮긴다.
이번에 승인된 설계 결정(선점 요청에 대해 ADR-009 후보 B + generation·fence, C1·C2)을 규칙·불변식 문서에 반영한다.

> ### ★ 저장소가 생겼다고 요청 경로의 I-18 이 완성된 것이 아니다
>
> TX0/TX1/TX2 를 여는 **운영 조정자가 없고**, 서비스 배선·REST·`Idempotency-Key` 헤더 처리·인증도 없다.
> 지금 어떤 요청도 이 저장소를 통과하지 않는다. 이 문서가 말하는 보장은 **저장소가 불려졌을 때**의 것이다.

**한 것**

- `modules/reservation-domain` — 순수 Java 계약 (`com.railgate.reservation.idempotency`)
  - `IdempotencyKeyPort` — 확보·조회·인수·성공 기록·실패 기록·포기
  - 값 타입: `IdempotencyKey`, `IdempotentOperation`(`HOLD_SEATS` 하나), `IdempotencyScope`, `RequestFingerprint`,
    `KeyOwnership(generation, fence)`, `IdempotencyKeyState`, `StoredHoldOutcome`(`Succeeded`/`Rejected`),
    `HoldRejectionCode`, `IdempotencyRecord`, `KeyWriteOutcome`
- `modules/reservation-infra`
  - `V7__idempotency_key.sql` — 운영 테이블. 기존 migration 은 수정하지 않았다
  - `JdbcIdempotencyKeyRepository` — 포트 구현
- 테스트: `JdbcIdempotencyKeyRepositoryTest`(36), `IdempotentHoldTransactionTest`(3)
- 문서: CLAUDE.md 규칙 16(C1), INVARIANTS I-18(C2), ADR-009 상태, 계약 문서, README·ARCHITECTURE 구현 상태

**하지 않은 것** — 운영 조정자, 서비스·빈 배선, REST, 인증, 응답 매핑(C3), 리스·보존 수치 확정(C4), 보존 기간 삭제
스케줄러, 메트릭(규칙 35), 해제·확정·결제 시작으로의 확장. fingerprint **계산**과 예외 → 업무 실패 **분류**는 저장소나
domain 으로 옮기지 않았다 — 요청을 아는 애플리케이션 계층의 몫이다. Task 2I-A 의 테스트 전용 구현은 그대로 보존했다.

---

## 2. 승인된 설계 결정의 반영

| # | 결정 | 반영 |
|---|---|---|
| 후보 B | 성공 결과를 본 작업과 같은 트랜잭션(TX1)에 소유권 조건부로 기록 | 포트 `completeSucceeded` 의 계약 |
| generation + fence | INSERT 마다 새 세대, 인수는 같은 세대에서 fence + 1 | V7 `generation` 열, `KeyOwnership`, 모든 조건부 UPDATE |
| C1 | 규칙 16: TX0 확보 → TX1 본 작업 + 성공 기록 → TX2 업무 실패 기록 또는 조건부 리스 포기 | CLAUDE.md 규칙 16 정정. TX2 실패 기록은 TX1 롤백 확인 시에만. 커밋 불명 장애의 포기는 완료 행을 바꾸지 않는 조건부 연산 |
| C2 | I-18 "CAS 만으로 재실행이 안전" 정정 | INVARIANTS I-18 보장 메커니즘 3·4 와 보장 범위(같은 키의 같은 세대) |
| C3 | 진행 중 HTTP 코드 | **미결정** |
| C4 | 리스·보존 수치 | **미결정**. 저장소는 리스를 매 호출 인자로 받는다 |

규칙 16 의 근거 문구도 고쳤다. 이전 문구는 "한 트랜잭션에 넣으면 중복 키 예외 후 기존 응답을 읽을 수 없다" 고
단정했는데, 그것은 DB·드라이버·트랜잭션 관리자 구성에 따라 다를 수 있다. 정정한 근거는 "같은 트랜잭션에서 안정적으로
읽는다고 **가정하지 않는다**" 이다.

---

## 3. 저장소 계약

### 3.1 스키마 (V7)

| 열 | 의미 |
|---|---|
| `user_id`, `operation`, `idempotency_key` | PK. 키 범위. 키·연산은 `ascii_bin` — 대소문자 구분 |
| `request_fingerprint` | 최초 요청의 SHA-256 16진수 |
| `state` | `IN_PROGRESS` / `COMPLETED` |
| `generation`, `fence` | 소유권 |
| `lease_until` | `IN_PROGRESS` 에서만 값 |
| `result_type`, `hold_id`, `sale_event_id`, `seat_count` | `COMPLETED` 에서만. 성공이면 셋 다, 업무 실패면 셋 다 NULL |
| `created_at`, `completed_at` | `DATETIME(3)`, 값은 DB `NOW(3)` |

**CHECK 4개** — 상태 값, `fence >= 1`, 결과 종류 값, 그리고 상태와 결과 필드의 일관성(`ck_idempotency_key_shape`).
저장소를 거치지 않은 쓰기도 어긋난 행을 만들 수 없게 하는 최후의 방어선이다.
**외래 키는 없다** — 사용자 테이블이 없고, 결과 열은 현재 상태가 아니라 최초 결과의 기록이다.
보존 기간 삭제용 인덱스는 삭제 작업이 없으므로 측정 없이 추가하지 않았다.

### 3.2 연산

| 연산 | SQL | 트랜잭션 참여 | 결과 |
|---|---|---|---|
| `tryInsert` | INSERT, 새 세대·fence 1·리스 `NOW(3)+lease` | 필수 | 소유권 / 중복이면 빈 값 |
| `find` | 잠금 없는 SELECT. 리스 만료는 DB 가 계산 | 불필요 | 스냅숏 |
| `takeOver` | `WHERE state='IN_PROGRESS' AND generation=? AND fence=? AND lease_until <= NOW(3)` | 필수 | (같은 세대, fence+1) / 빈 값 |
| `completeSucceeded` | `WHERE state='IN_PROGRESS' AND generation=? AND fence=?` | 필수 | `APPLIED` / `NOT_OWNER` |
| `completeRejected` | 위와 같다 | 필수 | 위와 같다 |
| `abandon` | 위와 같다, `lease_until = NOW(3)` | 필수 | 위와 같다 |

- **성공은 `affected_rows` 로만 판정**하고 사전 SELECT 로 대신하지 않는다. MySQL Connector/J 기본값
  (`useAffectedRows=false`)에서 이 값은 "일치한 행 수" 이며, 조건이 소유권 전체를 담으므로 일치는 곧 "현재 소유자" 다.
- **트랜잭션 참여 검사** — `JdbcUserHoldQuotaRepository` 와 같은 방식(`PROPAGATION_MANDATORY` 로 이 DataSource 의 활성
  트랜잭션을 묻는다). 아니면 SQL 전에 `IllegalStateException`. 저장소는 트랜잭션을 열거나 독립 커밋을 만들지 않는다.
  같은 판정 코드가 quota 저장소와 이 저장소에 따로 있다 — 공통화는 세 번째 사용처가 생길 때 판단한다.
- **중복과 장애의 구분** — `DuplicateKeyException`(MySQL 1062 의 Spring 번역)만 빈 값으로 바꾼다. 잠금 대기 초과·연결
  장애·제약 위반은 그대로 던진다. 중복 뒤 같은 트랜잭션의 상태는 보장하지 않는다 — 호출자는 TX0 를 끝내고 새 트랜잭션에서
  `find` 한다.
- **시간 정밀도** — 열이 `DATETIME(3)` 이므로 리스는 1ms 이상 밀리초 단위만 받는다. 그보다 정밀한 값은 반올림하지 않고 거부한다.
- **읽기 계약** — `find` 는 잠그지 않는다. 트랜잭션 안이면 그 격리 수준의 스냅숏이다. 스냅숏으로 판단한 뒤의 변경은 반드시
  그 스냅숏의 소유권을 조건으로 하는 연산으로 한다. `COMPLETED` 의 `leaseExpired` 는 언제나 `false` 다.

### 3.3 저장소가 보장하는 범위와 보장하지 않는 범위

| 보장한다 (저장소가 불려졌을 때) | 보장하지 않는다 |
|---|---|
| 한 키의 같은 세대에서 소유권 `(generation, fence)` 는 많아야 한 시도에게만 현재 값이다 | 요청 경로에서 TX0/TX1/TX2 를 올바른 순서로 여는 것 — 조정자가 없다 |
| 인수당한 작업자와 과거 세대 작업자의 기록·포기·인수는 0 건 | fingerprint 불일치·재생·진행 중 판정 — 호출자 몫 |
| 호출자 트랜잭션과 함께 커밋·롤백된다. 트랜잭션 밖 변경은 거부 | 보존 기간 이후의 재생 — 삭제되면 새 세대가 실행된다 |
| 성공 기록이 거절되면 같은 TX1 의 좌석·quota 도 롤백된다 (호출자가 예외로 롤백할 때) | 인증된 사용자와 `user_id` 의 일치 |

---

## 4. ★ TDD 기록

### RED

테스트 두 클래스를 먼저 작성하고 포트 구현을 `UnsupportedOperationException` 을 던지는 골격으로 두었다. 2026-10-12:

| 단계 | 조건 | 결과 |
|---|---|---|
| RED-1 | V7 없음, 골격 구현 | **39개 중 39개 실패** — 전부 `@BeforeEach` 의 `DELETE FROM idempotency_key` 에서 `BadSqlGrammarException`(테이블 없음). 행위까지 도달하지 못한 RED 라 아래 RED-2 를 따로 남겼다 |
| RED-2 | V7 추가, 골격 구현 | **39개 중 37개 실패** — 32개 `UnsupportedOperationException`, 트랜잭션 밖 거부 2개·리스 정밀도 거부 1개·조회 1개 등 어서션 실패. 통과 2개는 스키마만 보는 테스트(V7 적용, CHECK 거부) |

### GREEN

`JdbcIdempotencyKeyRepository` 구현 후 첫 실행에서 **2개 실패**했다. 둘 다 테스트 코드 결함이었다 —
`queryForMap` 이 `DATETIME(3)` 을 `LocalDateTime` 으로 돌려주는데 `Timestamp` 로 캐스팅했다(`ClassCastException`).
변환을 고친 뒤 **39개 모두 통과**. 저장소 구현은 그 사이 바꾸지 않았다.

새 동시성 방어를 만든 것이 아니라 Task 2I-A 에서 순진한 구현으로 실패를 재현한(RED-1~4) 방어를 운영 코드로 옮긴 것이다.
삭제·재생성 시나리오가 fence 만으로는 막히지 않는다는 것은 각 테스트가 "새 행의 fence 가 과거 소유권과 같은 1" 임을
함께 단언해 확인한다.

---

## 5. 검증 — `JdbcIdempotencyKeyRepositoryTest` (36개), `IdempotentHoldTransactionTest` (3개)

실제 MySQL 8.4 Testcontainers 와 Flyway(V1~V7). 리스 만료·보존 기간 경과는 DB 시각 기준으로 행의 시각 열을 옮겨 만들고,
경합은 래치로 출발을 맞춘다. `Thread.sleep` 은 없다.

| 절 | 확인한 것 |
|---|---|
| §1 생성·조회·복원 (10) | V7 적용, 새 키 = 새 세대·fence 1·진행 중·리스는 DB 시각 + lease, 조회가 소유권·DB 계산 리스 상태를 돌려줌, 없는 키 빈 값, 성공 결과 복원·`completed_at` 이 DB 시각, 업무 실패 3종 복원(성공 필드 NULL), 완료 행 재기록·포기 거절, 포기는 리스만 즉시 만료 |
| §2 격리 (3) | 대소문자 다른 키는 별개 행, 사용자 격리, 다른 연산의 행(직접 SQL)과 섞이지 않음 |
| §3 동시 INSERT·장애 (2) | 같은 키 16스레드 동시 INSERT → 소유 1, 나머지 예외 없이 빈 값. **잠금 대기 초과(3초)는 빈 값이 아니라 `PessimisticLockingFailureException`** |
| §4 인수 (5) | 리스 유효 시 인수 불가, 만료 시 같은 세대 fence 2, 인수당한 fence 의 성공·실패·포기·재인수 거절, 같은 스냅숏 8스레드 동시 인수 → 1, 완료 행 인수 불가 |
| §5 삭제·재생성 (8) | 과거 세대의 성공 기록·실패 기록·포기·삭제 전 스냅숏 인수가 모두 거절. 각각 새 요청 fingerprint 같음/다름. 스냅숏 인수는 새 행의 리스까지 만료시켜 세대 불일치로 막히는 것을 확인 |
| §6 트랜잭션 (5) | 트랜잭션 밖 변경 5종 SQL 전 거부·행 불변, 조회는 밖에서도 가능, 호출자 롤백 시 INSERT·완료 기록·인수 모두 사라짐, 다른 DataSource 의 트랜잭션 안에서는 거부 |
| §7 입력·스키마 (3) | 리스 0·음수·1.5ms 거부, null 거부, 상태·결과 불일치 행과 fence 0·알 수 없는 결과 종류를 CHECK 가 거부 |
| 통합 (3) | 한 TX1 에서 quota 확보 → 다좌석 선점 → 성공 기록. 현재 소유자면 함께 커밋. **인수당한 작업자·과거 세대 작업자면 성공 기록이 `NOT_OWNER` → TX1 롤백 → 좌석 AVAILABLE, 같은 TX1 에서 만든 quota 행까지 사라짐.** 새 세대 작업자는 정상 완료 |

통합 테스트의 TX1 은 테스트가 연다(`HoldSeatsService` 의 본 작업 순서 + 성공 기록을 마지막에). 운영 조정자가 아니다.
잠금 순서는 quota → seat(PK 오름차순) → 키 행이다.

### 기존 테스트 변경

- `UserHoldQuotaSchemaMigrationTest` 두 곳 — `migrate()` 가 최신까지 올리므로 현재 버전 단언을 `"6"` 에서 `"7"` 로 바꾸고,
  **V6 이 적용 이력에 있다는 단언을 추가**했다. 검증을 약화하지 않았다.
- `ReservationServiceApplicationTest` — 앱 컨텍스트의 Flyway 이력에 `"7"` 을 추가로 요구한다.
- Task 2I-A 의 테스트 전용 구현·테스트는 그대로다.

---

## 6. 전체 검증 기록

2026-10-12, `./gradlew check --rerun-tasks --max-workers=1` — `BUILD SUCCESSFUL`, 21 tasks executed.
`verifyArchitectureRules`(도메인 순수성·모듈 경계·버전 직접 명시 금지) 포함. `git diff --check` 통과.

| 모듈 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `apps:queue-service` | 1 | 0 | 0 | 0 |
| `apps:reservation-service` | 238 | 0 | 0 | 0 |
| `modules:reservation-domain` | 202 | 0 | 0 | 0 |
| `modules:reservation-infra` | 599 | 0 | 0 | 0 |
| **합계** | **1040** | **0** | **0** | **0** |

**테스트 수가 바뀐 이유** — TASK-002I-A §11-1 의 1001개에서 +39. 전부 `modules:reservation-infra` 의 신규
`JdbcIdempotencyKeyRepositoryTest`(36)와 `IdempotentHoldTransactionTest`(3)다. 기존 테스트를 지우거나 제외하지 않았다.
domain 값 타입의 단위 테스트는 따로 두지 않았다 — 검증 규칙은 저장소 테스트를 통해서만 실행된다.

Docker 는 다른 프로젝트 컨테이너가 떠 있는 상태에서 실행했고 OOM 은 없었다. 다른 컨테이너를 멈추거나 Docker 설정을 바꾸지 않았다.

---

## 7. 남은 것

### 아직 연결되지 않은 경로

| 항목 | 상태 |
|---|---|
| **운영 조정자** (TX0/TX1/TX2, fingerprint 비교·재생·진행 중 판정, 예외 → 업무 실패 분류) | 없다. Task 2I-A 의 테스트 전용 구현만 있다 |
| **서비스·빈 배선** | 없다. `IdempotencyKeyPort` 빈이 등록돼 있지 않다 |
| **REST·`Idempotency-Key` 헤더(누락 400)·응답 직렬화** | 없다 |
| **인증 주체에서의 `userId`** | 없다. 지금은 호출자가 넣는 값을 그대로 쓴다 |
| **C3** 진행 중 응답의 HTTP 코드 | 미결정 |
| **C4** 리스·보존 수치 | 미결정. 30초·24시간은 가정 |
| **보존 기간 삭제** | 없다. 후속 계약: `COMPLETED` 이면서 보존 기간이 지난 행만, `IN_PROGRESS` 는 지우지 않는다 |
| **관측성** (규칙 35) | 없다. 후속: 재생·진행 중·불일치·인수·소유권 거절(`NOT_OWNER`) 계수기, 리스 만료 인수 비율, 키 테이블 크기 |
| 해제·확정·결제 시작에 같은 계약 적용 | 판단 전 |

### 검증하지 않은 것

- **커밋 결과 불명**(COMMIT 중 연결 끊김) — 재현하지 않았다. 안전성은 구조적 논증(성공 기록이 같은 트랜잭션, 포기는 완료 행을 바꾸지 않음)에 기댄다
- 키 행 추가로 늘어난 TX1 길이·잠금 보유 시간, 대규모 혼합 경합, 데드락 부재 — 측정·증명하지 않았다
- `useAffectedRows=true` 등 드라이버 설정을 바꾼 경우의 판정
- READ COMMITTED 등 다른 격리 수준에서의 `find` 스냅숏 의미
- UUID 세대 충돌 — 무시할 수 있다는 가정

---

## 8. 재현 명령

```bash
./gradlew :modules:reservation-infra:test --tests "*.infra.idempotency.*"
./gradlew :modules:reservation-infra:test --tests "*UserHoldQuotaSchemaMigrationTest"
./gradlew check --rerun-tasks --max-workers=1
```
