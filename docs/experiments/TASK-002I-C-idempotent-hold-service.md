# TASK-002I-C — 선점 멱등성 조정자 (서비스 수준 연결)

> 관련: [ADR-009](../adr/ADR-009-idempotency-key.md) (C1·C2 채택, C3·C4 미결정), [IDEMPOTENCY_CONTRACT.md](../IDEMPOTENCY_CONTRACT.md),
> [INVARIANTS.md](../INVARIANTS.md) I-8·I-12·I-18, CLAUDE.md 규칙 7·9·14~17·25~27·35,
> [TASK-002I-A](TASK-002I-A-hold-idempotency-contract.md) — 계약·장애 구간 실험(테스트 전용 구현),
> [TASK-002I-B](TASK-002I-B-idempotency-store.md) — 운영 저장소 V7·`JdbcIdempotencyKeyRepository`

## 1. 목적과 범위

운영 `IdempotencyKeyPort` 와 `HoldSeatsService` 를 묶는 **선점 멱등성 조정자 `IdempotentHoldService`** 를 운영 코드로 만들고
Spring 빈으로 배선한다. 키·fingerprint 판정, TX0/TX1/TX2 경계, 결과 재생, 리스 인수와 소유권 상실을 처리한다.

> ### ★ 서비스 수준 연결이지 HTTP 요청 경로의 완성이 아니다
>
> 조정자 빈은 있지만 **이 빈을 부르는 HTTP 진입점이 없다.** `Idempotency-Key` 헤더 처리(누락 400), 인증, 응답
> 직렬화·상태 코드(ADR-009 C3)는 후속이다. 사용자 id 는 아직 `HoldSeatsCommand` 에서 오며 **인증된 주체와 대조하지
> 않는다** — 남의 사용자 id 로 그 사용자의 키 범위를 쓸 수 있다는 한계가 남는다.

**한 것** (`apps/reservation-service`, 패키지 `com.railgate.reservation.application.idempotency`)

- `IdempotentHoldService` — 조정자. 입력은 유효한 `IdempotencyKey` 와 기존 `HoldSeatsCommand`
- `IdempotentHoldResult` — `Executed`·`Replayed`·`InProgress`·`FingerprintMismatch`·`OwnershipLost`. HTTP 코드 없음.
  `Executed` 와 `Replayed` 는 같은 저장 결과(`StoredHoldOutcome`)를 담는다
- `HoldRequestFingerprints` — `SHA-256("hold-seats/v1:" + 숫자 오름차순 seatId)`
- `HoldIdempotencyDecision` — 판정 순서 불일치 → 재생 → 진행 중 → 인수 (순수 함수)
- `HoldRejectionClassifier` — 계약의 세 예외만, **던져진 예외 자체만** 업무 실패로 분류
- `IdempotentHoldProperties` — `railgate.idempotency.hold.lease` (기본 30초, **미검증 가정**)
- 배선 — `ReservationPersistenceConfig` 에 `IdempotencyKeyPort` 빈(`JdbcIdempotencyKeyRepository`), `IdempotentHoldConfig` 에 조정자 빈

**하지 않은 것** — REST, 인증, 응답 직렬화, 보존 기간 자동 삭제, 해제·확정·결제 시작으로의 확장, 감사 로그(규칙 32).
`HoldSeatsService` 의 quota·좌석 처리와 잠금 순서, 저장소의 소유권·트랜잭션 계약은 바꾸지 않았다.
운영 코드는 Task 2I-A 의 테스트 전용 구현에 의존하지 않는다 (서로 다른 패키지, 테스트 소스 참조 없음).

---

## 2. 흐름

```text
hold(key, command)
  바깥 트랜잭션이 있으면 → IllegalStateException (키를 건드리기 전)
  scope = (command.userId, HOLD_SEATS, key),  fingerprint = SHA-256("hold-seats/v1:" + 정렬 seatId)

  TX0 (REQUIRES_NEW, 최대 3라운드)
    tryInsert → 소유권(새 세대, fence 1) 이면 실행으로
    빈 값(중복)  → 그 TX0 를 끝내고 새 트랜잭션에서 find
      없음       → 다음 라운드 (보존 기간 삭제)
      불일치     → FingerprintMismatch
      완료       → Replayed(저장 결과)
      진행 중    → InProgress
      만료       → 새 트랜잭션에서 takeOver(스냅숏 소유권) → 성공이면 실행으로, 실패면 다음 라운드
    라운드 소진  → InProgress            (DB 장애는 라운드와 무관하게 그대로 전파)

  TX1 (REQUIRED)  HoldSeatsService.hold → completeSucceeded(소유권)
                  NOT_OWNER → TX1 전체 롤백 → OwnershipLost
                  커밋      → Executed(Succeeded)
  업무 실패 (TX1 이 롤백을 마친 뒤 다시 던진 계약 예외)
    TX2 (REQUIRES_NEW) completeRejected(소유권) → Executed(Rejected) / NOT_OWNER → OwnershipLost
  그 외 RuntimeException (TX1 장애, TX1 롤백 실패, 커밋 결과 불명, TX2 장애)
    결과 저장 없음 → REQUIRES_NEW 에서 abandon(소유권) → 원래 예외를 그대로 던진다
    abandon 도 실패하면 원래 예외에 suppressed 로 붙인다
  Error 는 잡지 않는다 — 프로세스 사망과 같게, 키는 리스 만료 뒤 인수된다
```

### 판단 근거

- **업무 실패 기록은 TX1 롤백이 확인된 뒤에만** — `TransactionTemplate` 은 롤백을 마친 뒤 원래 예외를 다시 던진다.
  롤백 자체가 실패하면 롤백 예외가 대신 나오고, 그것은 계약 예외가 아니므로 장애로 다룬다.
- **업무 실패 분류는 원인 사슬을 보지 않는다** — `DataIntegrityViolationException(cause = SeatUnavailableException)` 같은
  DB 오류를 업무 실패로 굳히면 커밋 여부가 불확실한 상황을 최종 결과로 만든다. 그래서 `instanceof` 는 던져진 예외에만 적용한다.
- **커밋 결과 불명에서도 리스 포기는 안전하다** — 포기는 `state='IN_PROGRESS'` 와 소유권 조건이다. 실제로 커밋됐다면 행은 이미
  `COMPLETED` 라 포기가 `NOT_OWNER` 이고, 커밋되지 않았다면 상태 변경이 없다.
- **소유권은 확보한 값 그대로** — TX0 가 받은 `KeyOwnership`(세대·fence)을 성공 기록·실패 기록·포기에 그대로 넘긴다.
- **잠금 순서** — TX1 은 `HoldSeatsService` 의 `quota → seat(PK 오름차순)` 뒤에 키 행을 잠근다. TX0·TX2·포기는 키 행만 쥔다.
- **커넥션** — TX0·TX1·TX2·포기는 순차다. 요청 하나가 동시에 쥐는 커넥션은 최대 1개다 (§4 풀 크기 1 테스트).

---

## 3. 설정과 관측성

### 리스

`railgate.idempotency.hold.lease` (기본 `30s`). 30초는 잠금 대기 상한 3초(규칙 9) × 문장 수보다 넉넉하다는 것 외에
**측정 근거가 없는 가정값**이다. **ADR-009 C4 는 미결정이며 이 값으로 확정된 것이 아니다.** 저장소의 정밀도 계약(1ms 이상,
밀리초 단위)을 어기면 조정자 생성자가 거부해 기동이 실패한다.

### 메트릭 (규칙 35)

| 이름 | 태그 | 의미 |
|---|---|---|
| `railgate.idempotency.hold.calls` | `result` = `executed`·`replayed`·`in_progress`·`mismatch`·`ownership_lost`·`failed` | 호출의 최종 결과 |
| `railgate.idempotency.hold.attempts` | — | TX1 을 열기 직전에 증가하는 **본 작업 실행 시도** 수 |
| `railgate.idempotency.hold.committed` | `outcome` = `succeeded`·`seat_unavailable`·`quota_exceeded`·`sale_event_scope_rejected` | 성공 결과(TX1) 또는 업무 실패 결과(TX2)의 기록 트랜잭션이 **정상 반환돼 조정자가 커밋 완료를 확인한** 수 |
| `railgate.idempotency.hold.takeovers` | — | 리스 만료 행 인수 성공 |
| `railgate.idempotency.hold.not_owner` | `write` = `succeeded`·`rejected`·`takeover` | 소유권 조건부 쓰기의 거절 |
| `railgate.idempotency.hold.abandons` | `outcome` = `applied`·`not_owner`·`failed` | 장애 뒤 리스 포기 |

**`committed` 는 DB 에 실제로 반영된 커밋을 모두 세지 않는다.** 커밋이 반영된 뒤 실패로 보고되면(커밋 결과 불명) 증가하지 않는다.
그래서 **`attempts − committed` 는 TX1 롤백 수가 아니다.**

| 사례 | attempts | committed | 차이 | 실제 TX1 |
|---|---:|---:|---:|---|
| 업무 실패 — TX1 롤백 + TX2 기록 성공 | 1 | 1 | 0 | 롤백됨 |
| TX1 이 실제 커밋됐으나 호출자에게 실패로 보고됨 | 1 | 0 | 1 | 커밋됨 |

`abandons{outcome=not_owner}` 도 커밋 성공을 확정하는 지표가 아니다. 포기 시점에 이 소유권(세대·fence)으로 바꿀 진행 중 행이
없었다는 뜻일 뿐이며, 이미 완료된 경우(커밋 결과 불명이었지만 실제로 커밋됨)와 다른 작업자가 리스를 인수한 경우를 구분하지 않는다.
실제 결과는 키 행을 조회해야 알 수 있다. **태그는 고정 집합의 값만 쓴다** — 사용자 id·멱등키·holdId 는 태그에도
로그에도 남기지 않는다(단위 테스트로 확인). 로그는 소유권 상실과 포기 실패의 WARN 두 개뿐이며 식별값이 없다.

---

## 4. ★ TDD 기록과 검증

### RED → GREEN

| 단계 | 대상 | 결과 |
|---|---|---|
| RED | 골격 구현(메서드가 `UnsupportedOperationException`) + `IdempotentHoldServiceTest` | **29개 중 28개 실패** — 19개 `UnsupportedOperationException`, 9개 어서션 실패. 통과 1개는 중복 좌석이 명령 단계에서 거부되는 테스트(조정자와 무관) |
| GREEN | 구현 | 단위 29개 통과, 이어서 작성한 MySQL 통합 17개·설정 5개 통과 |

### 변이 실행 (규칙 27)

TX1 의 성공 기록이 `NOT_OWNER` 여도 무시하도록 임시로 바꿔 `application.idempotency.*` 를 돌렸다 — **46개 중 3개 실패**:
통합 §5 "인수당한 구 작업자의 선점은 롤백되고 새 작업자만 커밋한다", §5 "과거 세대의 지연된 기록"(success),
단위 §5 "성공 기록이 거절되면 TX1 을 롤백". 변이는 즉시 되돌렸고(`cmp` 로 동일 확인) 커밋에 들어가지 않았다.

### 단위 — `IdempotentHoldServiceTest` (29개, DB 없음)

가짜 포트(소유권 조건을 흉내)와 가짜 트랜잭션 관리자("커밋하면 반영, 롤백하면 소멸", 트랜잭션 번호 기록)를 쓴다.

| 절 | 확인한 것 |
|---|---|
| §1 fingerprint (4) | 계약 문자열의 SHA-256, 순서 정규화, **숫자 정렬**(`[10,9]` → `9,10`), 중복 좌석은 명령 단계 거부·키 미소비 |
| §2 판정 (2) | 불일치가 상태보다 먼저, 완료 → 재생·진행 중 → 대기·만료 → 인수 |
| §3 분류 (2) | 계약의 세 예외만 업무 실패. 잠금 대기 초과, **업무 예외가 원인으로 붙은 DB 오류**, 원인 중첩 예외는 업무 실패 아님 |
| §4 정상 경로 (5) | TX0 확보 → TX1 선점+성공 기록 같은 트랜잭션, 재생(실행 1회), 불일치, 사용자 격리, 진행 중 |
| §5 소유권 (5) | 만료 인수(같은 세대 fence+1)·인수한 소유권으로 기록, **성공 기록 거절 → TX1 롤백·좌석 변경 없음**, 실패 기록 거절 → 소유권 상실, 인수 경쟁 3라운드 소진 → 진행 중, 중복 뒤 행 소멸 → 재삽입 |
| §6 업무 실패 (1) | TX1 롤백 → 별도 TX2 기록 → 재생, 좌석이 비어도 재실행 없음 |
| §7 장애 (7) | 같은 소유권으로 포기·원래 예외 전파, 중첩 원인 오분류 없음, 포기 실패는 suppressed, TX2 실패 → 포기·전파, **커밋 결과 불명 → 포기 `NOT_OWNER`·재생**, 키 확보 DB 장애 전파, `Error` 는 잡지 않고 키 불변 |
| §8 경계 (3) | 바깥 트랜잭션 거부(키 호출 0), 리스 0·음수·1.5ms 거부, 메트릭 태그 키·값에 고유값 없음 |

### 통합 — `IdempotentHoldServiceIntegrationTest` (17개, Spring 빈 + MySQL 8.4 + Flyway V1~V7)

기본은 주입받은 빈이다. 순서 고정·장애 주입이 필요한 테스트만 **같은 빈들**(트랜잭션 프록시가 붙은 `HoldSeatsService`, JDBC
저장소, 트랜잭션 관리자)로 조정자를 하나 더 만들고 본 작업 앞뒤에 테스트 훅을 끼운다. 운영 코드에 실패 스위치는 없다.
리스 만료는 `lease_until` 을 DB 시각 기준 과거로 옮겨 만들고 경합은 래치로 고정한다. `Thread.sleep` 은 없다.

| 절 | 확인한 것 |
|---|---|
| §1 배선 (2) | `IdempotencyKeyPort` 빈이 `JdbcIdempotencyKeyRepository`, `HoldSeatsService` 가 트랜잭션 프록시. 빈 호출로 선점·성공 기록 함께 커밋, `committed` 메트릭 증가 |
| §2 재생 (5) | 같은 키·같은 요청 재생(좌석 순서만 다름 포함, quota 1회), 다른 요청 불일치·좌석 미변경, 사용자 격리(B 는 A 의 holdId 를 받지 않음), **성공 뒤 홀드 해제 → 재선점 없이 최초 holdId 재생**, 업무 실패 저장·좌석이 비어도 재생 |
| §3 장애 (3) | TX0 뒤 중단(`Error`) → 리스 동안 진행 중 → 만료 뒤 인수(fence 2), 재시도 가능한 장애 → TX1 롤백·리스 즉시 포기·바로 인수, **커밋 결과 불명**(트랜잭션 관리자 데코레이터가 COMMIT 실행 후 실패 보고) → 행은 `COMPLETED`·fence 1 그대로, 재요청 재생 |
| §4 동시 (1) | 같은 키 16스레드(래치) → 실행 시도 1, `Executed` 1, 나머지 진행 중 또는 같은 holdId 재생, **예외 0**, quota 2 |
| §5 소유권 (4) | **같은 세대 인수**: 구 작업자의 성공 기록 거절 → 좌석·quota 롤백, 새 작업자 정상 완료·재생. **삭제·재생성 뒤 과거 세대**의 지연된 성공·업무 실패·장애(포기) 세 갈래 모두 새 세대 행(세대·상태·리스) 불변, 과거 세대 선점 미잔존, 새 작업자 정상 완료 |
| §6 경계 (2) | 바깥 트랜잭션 거부(키 행 0, 좌석 불변), **풀 크기 1** 에서 성공·재생·업무 실패·장애 포기·인수가 모두 끝남 |

§6 풀 크기 1 실험은 `HoldSeatsService` 를 직접 `new` 해서 쓴다. 프록시가 없어도 조정자의 TX1 이 같은 DataSource 에 연
트랜잭션에 저장소들이 참여한다.

### 설정 — `IdempotentHoldConfigTest` (5개, 경량 컨텍스트)

`ApplicationContextRunner` 로 실제 프로퍼티 바인딩과 컨텍스트 시작을 검증한다. 기본 30초, `1500ms` 바인딩, `0s`·`-1s`·`1500us`
는 컨텍스트 시작 실패(근본 원인 `IllegalArgumentException`).

---

## 5. 전체 검증 기록

2026-10-22, `./gradlew check --rerun-tasks --max-workers=1` — `BUILD SUCCESSFUL`, 21 tasks executed,
`verifyArchitectureRules`(도메인 순수성·모듈 경계·버전 직접 명시 금지) 포함. `git diff --check` 통과.

| 모듈 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `apps:queue-service` | 1 | 0 | 0 | 0 |
| `apps:reservation-service` | 289 | 0 | 0 | 0 |
| `modules:reservation-domain` | 202 | 0 | 0 | 0 |
| `modules:reservation-infra` | 621 | 0 | 0 | 0 |
| **합계** | **1113** | **0** | **0** | **0** |

**테스트 수가 바뀐 이유** — TASK-002I-B §9 의 1062개에서 +51. 전부 `apps:reservation-service` 의 신규
`IdempotentHoldServiceTest`(29)·`IdempotentHoldServiceIntegrationTest`(17)·`IdempotentHoldConfigTest`(5)다.
기존 테스트를 지우거나 제외하지 않았다.

**첫 전체 실행은 환경 장애로 무효였다.** 같은 날 첫 실행에서 `reservation-infra` 의 MySQL 테스트 컨테이너가 기존 실험 테스트
(TASK-002G-E-B1, 좌석 6만 행 삽입) 도중 **종료 코드 137(메모리 부족으로 강제 종료)** 로 죽었고, 이후 infra 테스트가 연결 실패로
줄줄이 실패했다. Docker 메모리는 약 2GB 이고 다른 프로젝트 컨테이너 5개가 약 500MB 를 쓰고 있었다. 실행을 중단하고 **한 번만**
다시 돌려 위 결과를 얻었다(OOM·연결 실패 0건). 다른 컨테이너나 Docker 설정은 바꾸지 않았다. 이 OOM 은 이번 변경과 무관한
기존 테스트에서 났지만, 같은 메모리 조건에서 재발할 수 있다.

---

## 6. 남은 것

| 항목 | 상태 |
|---|---|
| **HTTP 진입점·`Idempotency-Key` 헤더(누락 400)** | 없다. 조정자를 부르는 요청 경로가 없다 |
| **인증 주체의 `userId`** | 없다. 명령의 값을 그대로 쓴다 — 인증·소유자 검증 완료가 아니다 |
| **C3** 진행 중·소유권 상실 응답의 HTTP 코드, 응답 직렬화 | 미결정 |
| **C4** 리스·보존 수치 | 미결정. 30초·24시간은 가정. 리스 길이와 TX1 소요의 실측이 필요하다 |
| **보존 기간 삭제** | 없다. `COMPLETED` 이면서 보존 기간이 지난 행만 지우는 계약만 있다 |
| **감사 로그**(규칙 32) | 없다. 선점 전이가 `seat_state_log` 에 남지 않는다 |
| **MDC·구조화 로그**(규칙 33) | 없다. 조정자는 식별값 없는 WARN 두 개만 남긴다 |
| 해제·확정·결제 시작으로의 확장 | 판단 전 |

### 검증하지 않은 것

- **실제 COMMIT 중 연결 단절** — 재현하지 않았다. 커밋 결과 불명은 트랜잭션 관리자 데코레이터가 COMMIT 을 실행한 뒤 실패를
  보고하는 방식으로만 흉내 냈다. 안전성은 "성공 기록이 같은 트랜잭션, 포기는 완료 행을 바꾸지 않음" 이라는 구조적 논증에 기댄다
- **실제 프로세스 종료**(JVM kill·네트워크 분할) — `Error` 를 던지는 훅으로 흉내 냈다
- **동시성 규모** — 같은 키 16스레드 1회. 대규모 혼합 경합, 키 행 잠금이 더해진 TX1 길이·잠금 보유 시간, 데드락 부재는 측정·증명하지 않았다
- **리스 30초가 TX1 최대 소요보다 길다는 것** — 측정하지 않았다
- 업무 실패 저장 중 `SALE_EVENT_SCOPE_REJECTED` 를 최종으로 두는 것은 좌석이 삭제·이동되지 않는다는 **가정**에 기댄다

---

## 7. 재현 명령

```bash
./gradlew :apps:reservation-service:test --tests "*.application.idempotency.IdempotentHoldServiceTest"
./gradlew :apps:reservation-service:test --tests "*.application.idempotency.IdempotentHoldServiceIntegrationTest"
./gradlew :apps:reservation-service:test --tests "*.application.idempotency.IdempotentHoldConfigTest"
./gradlew check --rerun-tasks --max-workers=1
```
