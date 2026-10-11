# TASK-002I-A — 선점 요청의 멱등성 계약과 장애 구간 검증

> 관련: [ADR-009](../adr/ADR-009-idempotency-key.md) (결정 대기), [IDEMPOTENCY_CONTRACT.md](../IDEMPOTENCY_CONTRACT.md),
> [INVARIANTS.md](../INVARIANTS.md) I-8·I-9·I-12·I-18, [REQUIREMENTS.md](../REQUIREMENTS.md) FR-2.9,
> CLAUDE.md 규칙 14~18, [TASK-002H-A](TASK-002H-A-hold-application-service.md) — 선점 서비스

## 1. 목적과 범위

`HoldSeatsService` 의 선점 요청을 첫 적용 대상으로, **멱등성 계약을 정하고 3단 트랜잭션의 장애 구간을 실제 MySQL 에서
재현·비교한다.** 산출물은 ADR, 계약 문서, 실행 가능한 단위·MySQL 테스트다.

**한 것**

- [ADR-009](../adr/ADR-009-idempotency-key.md) — 문제, 대안 5개 비교, 선택 후보와 포기하는 것, **기존 규칙과의 충돌 4건(결정 대기)**
- [IDEMPOTENCY_CONTRACT.md](../IDEMPOTENCY_CONTRACT.md) — 키 범위, fingerprint, 상태·판정 순서, 결과 분류, 보존 기간, 응답 제안
- 테스트 전용 구현 (`apps/reservation-service/src/test/.../application/hold/idempotency/`)
  - 테스트 전용 테이블 `task2ia_idempotency_key` — 테스트가 `CREATE TABLE IF NOT EXISTS` 로 만든다. Flyway·운영 스키마와 무관
  - `Task2iaNaiveIdempotentHold` — **규칙 16 그대로**, fence 는 TX2 에서만 (RED 대조군)
  - `Task2iaAtomicCompletionIdempotentHold` — **성공 결과를 TX1 에서 fence 조건부 기록** (선택 후보 B)
  - 두 구현이 공유하는 TX0 `Task2iaKeyAcquisition`, 순수 계약 타입(키·범위·fingerprint·판정·결과)
- `IdempotencyContractTest` (단위 23개), `HoldIdempotencyFailureWindowTest` (MySQL 20개)

**하지 않은 것** — 운영 migration, 운영 저장소·서비스 코드, HTTP 컨트롤러·헤더 처리, 인증, 응답 직렬화, PG, Redis·Kafka,
보존 기간 삭제 작업, 메트릭(규칙 35), 해제·확정·결제 시작으로의 범용화.
**운영 코드는 바꾸지 않았다** — `HoldSeatsService` 의 정합성 로직, 만료 스케줄러 기본 비활성(`enabled=false`) 모두 그대로다.
(같은 브랜치의 별도 docs 커밋이 `HoldSeatsService` **Javadoc** 의 트랜잭션 참여 설명만 정정했다.)

---

## 2. 계약 요약

[IDEMPOTENCY_CONTRACT.md](../IDEMPOTENCY_CONTRACT.md) 가 원문이다.

| 항목 | 결정 |
|---|---|
| 키 범위 | (인증된 사용자, `HOLD_SEATS`, 클라이언트 키). 다른 사용자의 같은 키는 별개 |
| 키 비교 | ASCII 영숫자·`-`·`_` 8~64자, 바이트 비교. 누락·형식 위반은 400, 키 미소비 |
| fingerprint | `SHA-256("hold-seats/v1:" + 정렬한 seatId)`. 순서 정규화, 중복 좌석은 명령 단계 400 |
| 판정 순서 | 불일치 → 완료(재생) → 진행 중 → 리스 만료(인수) |
| 저장 | 성공은 TX1, 업무 실패는 TX2, 재시도 가능한 장애는 저장 안 함(리스 즉시 포기) |
| 보존 | 24시간 **가정**, 리스 30초 **가정**. 삭제 후 보장 없음 |

---

## 3. 두 구현의 차이

```text
             TX0 (공통)          TX1                                   TX2
순진한 구현   INSERT / 인수        hold()                                성공·실패 기록 (fence 검사)
선택 구현     INSERT / 인수        hold() + 성공 기록 (fence 검사, 0 → 롤백)   업무 실패 기록·리스 포기 (fence 검사)
```

TX0 는 같은 클래스를 쓴다. **차이는 성공 결과를 어느 트랜잭션에 기록하고 fence 를 어디서 확인하느냐 하나뿐이다.**

순서 고정은 테스트 전용 `Task2iaFaultPoints` 로 한다. 지점은 `AFTER_TX0`, `TX1_BEFORE_WORK`, `TX1_BEFORE_COMMIT`,
`AFTER_TX1_COMMIT`, `BEFORE_TX2`, `IN_TX2` 이고, 테스트가 래치로 멈추거나 `SimulatedCrash`(`Error`)를 던진다.
`SimulatedCrash` 는 구현의 `catch (RuntimeException)` 에 잡히지 않아 그 뒤의 코드가 실행되지 않고, 열린 트랜잭션은
롤백된다 — 실제 크래시에서 커넥션이 끊겨 미커밋 트랜잭션이 롤백되는 것을 흉내 낸다.
**리스 만료는 `Thread.sleep` 이 아니라 `lease_until` 을 DB 시각 기준 과거로 옮겨** 만들고, 판정은 언제나 `NOW(3)` 이다.

---

## 4. ★ RED — 순진한 구현의 실패 재현

`HoldIdempotencyFailureWindowTest` §1. 세 테스트 모두 **잘못된 결과를 관측했다고 단언**하며 통과한다
(기존 2H-A·B·C 의 RED 대조군과 같은 방식).

| # | 장애 | 관측 |
|---|---|---|
| RED-1 | TX1 커밋 직후 중단 → 리스 만료 → 재요청 | 본 작업 **2회 실행**. 재실행이 자기 홀드에 막혀 `SEAT_UNAVAILABLE` 을 **최초 결과로 기록**. 좌석은 아무도 `holdId` 를 모르는 첫 홀드가 쥐고, quota 2 소진 — **결과 유실** |
| RED-2 | RED-1 + 재요청 전 첫 홀드 해제 | 재실행이 **새 `holdId` 로 다시 선점을 커밋** — 한 키에 상태 변경 2회 (I-18 위반) |
| RED-3 | 구 작업자가 TX1 안에서 멈춤 → 리스 만료 → 인수자(fence 2)가 TX0 통과 → 구 작업자 재개 | **fence 1 의 TX1 이 인수 이후에 커밋됨.** TX2 는 fence 불일치로 기록을 버렸고, 인수자는 그 홀드에 막혀 `SEAT_UNAVAILABLE` 기록 — **TX2 만의 fence 는 TX1 중복 실행을 막지 못한다** |

이 결과가 I-18 보장 메커니즘 3("재실행되어도 CAS 가 `affected=0` 이므로 안전")의 반례다. ADR-009 C2.

---

## 5. 선택 구현 검증 — `HoldIdempotencyFailureWindowTest` §2~§5

| 절 | 테스트 | 확인한 것 |
|---|---|---|
| §2 장애 구간 (7) | TX0 이후 중단 | 리스 동안 `InProgress`(실행 0회), 만료 후 인수(fence 2)해 1회 실행 |
| | TX1 롤백(업무 실패) | `SEAT_UNAVAILABLE` 저장, 부분 선점·quota 행 없음. 좌석이 빈 뒤 재요청도 **재생**(실행 1회) |
| | 상한 초과 | `QUOTA_EXCEEDED` 저장, quota 4 그대로 |
| | **TX1 커밋 직후 중단** (RED-1 과 같은 장애) | 리스 만료를 기다리지 않고 **즉시 최초 `holdId` 재생**, 실행 1회, quota 2 |
| | **TX1 커밋 직후 중단 + 홀드 해제** (RED-2 와 같은 장애) | 재생만 하고 **다시 선점하지 않음**. 좌석 `AVAILABLE` — 재생은 현재 상태가 아니다 |
| | TX2 실패 후 재요청 | 키 `IN_PROGRESS`·fence 1, 첫 시도는 아무것도 바꾸지 않음. 리스 동안 `InProgress`, 만료 후 인수해 성공 |
| | 재시도 가능한 장애(커밋 직전 `CannotAcquireLockException` 주입) | 완료 기록까지 함께 롤백, 리스 즉시 만료, 다음 요청이 바로 인수(fence 2) |
| §3 리스 인수 (3) | **살아 있는 구 작업자** (RED-3 과 같은 교차) | 구 작업자 `Superseded`, **그의 선점·quota 모두 롤백**, 인수자만 커밋. 재요청은 인수자의 `holdId` |
| | 늦은 결과 기록 | 구 작업자의 늦은 `SEAT_UNAVAILABLE` 기록이 fence 2 의 성공 결과를 덮지 않음 |
| | 완료 기록을 쥔 TX1 뒤의 요청 | 같은 키 요청이 TX0 중복 검사에서 **잠금을 기다리는 것을 `performance_schema.data_lock_waits` 로 관측**한 뒤 커밋을 풀었고, 그 요청은 인수하지 않고 **재생** |
| §4 동시성·격리 (5) | 같은 키 32스레드 동시 요청 (래치) | **실행 1회**, `Executed` 1개, 나머지는 `InProgress` 또는 같은 `holdId` 재생, **예외 0건** |
| | 같은 키·다른 좌석 / 순서만 다름 | 불일치 거부·좌석 미변경 / 같은 `holdId` 재생 |
| | 진행 중인 키에 다른 요청 | 리스가 만료됐어도 인수하지 않고 불일치, fence 1 그대로 |
| | 사용자 간 격리 | B 의 같은 키는 A 의 `holdId` 를 받지 않고 자기 결과(`SEAT_UNAVAILABLE`)를 가짐. 행 3개 |
| | 보존 기간 이후 | 행 삭제 후 같은 키는 새로 실행됨 — **보장 범위는 보존 기간까지** |
| §5 커넥션 (2) | 바깥 트랜잭션 안에서 호출 | 키를 만들기 전에 `IllegalStateException` |
| | **풀 크기 1** | 성공·재생·업무 실패·TX0 중단 후 인수가 모두 끝남 — TX0/TX1/TX2 가 겹쳐 커넥션 2개를 잡는 순간이 없다 |

§5 의 풀 크기 1 실험은 `HoldSeatsService` 를 **직접 `new`** 해서 쓴다. 프록시가 없지만 TX1 의 `TransactionTemplate` 이
같은 DataSource 에 트랜잭션을 열어 두므로 저장소들이 그 커넥션에 참여하고, quota 저장소의 참여 검사도 통과했다.
같은 브랜치의 docs 커밋이 정정한 "프록시 유무가 아니라 실제 참여 여부" 설명을 실행으로 확인한 셈이다.

---

## 6. 변이 실행 — 선택 구현의 테스트가 실제로 실패할 수 있는가

규칙 27 에 따라, 선택 구현의 TX1 완료 기록을 지우고 **성공 결과를 TX2 에서만 fence 조건부로 기록**하도록(=순진한 구현의
성공 경로) 임시로 바꿔 `HoldIdempotencyFailureWindowTest` 를 돌렸다. 2026-10-11, **20개 중 4개 실패**:

| 실패한 테스트 | 관측 |
|---|---|
| §2 TX1 커밋 직후 중단돼도 재요청은 최초 `holdId` 를 재생한다 | 재생 대신 `InProgress` |
| §2 TX1 커밋 직후 중단되고 홀드가 풀려도 다시 선점하지 않는다 | 재생 대신 `InProgress` |
| §3 리스를 빼앗긴 구 작업자의 TX1 은 롤백되고 인수자만 커밋한다 | `Superseded` 기대, 구 작업자가 `Executed(Succeeded)` — 선점 커밋됨 |
| §3 완료 기록을 쥔 TX1 뒤의 요청은 잠금을 기다렸다가 재생한다 | 키 행 잠금이 없어 대기 요청이 리스 만료 키를 **인수해 재실행**, 구 작업자의 홀드에 막혀 `Executed(SEAT_UNAVAILABLE)` |

변이는 즉시 원래 파일로 되돌렸고(`cmp` 로 동일 확인) 커밋에 들어가지 않았다. 나머지 16개는 두 구현이 공유하는 TX0·
업무 실패 경로·격리를 검증하므로 이 변이로는 실패하지 않는 것이 맞다.

---

## 7. 주장의 3층 구조

| 층 | 주장 | 근거 |
|---|---|---|
| **구조적 논증** | 한 키에서 선점을 커밋하는 시도는 많아야 하나다. TX1 커밋 ⇔ 성공 결과 기록 | 같은 트랜잭션의 두 쓰기 + 키 행 조건부 UPDATE 의 행 잠금 직렬화 ([ADR-009](../adr/ADR-009-idempotency-key.md) §3.1) |
| **결정적 검증** | RED-1·2·3 의 장애에서 선택 구현은 재생·롤백한다 | 래치·실패 주입으로 순서를 고정한 테스트, 변이 실행 4건 실패 |
| **통계적 관측** | 같은 키 동시 요청에서 실행 1회·예외 0건 | 32스레드 1회 실행. 반증에 실패한 것이지 증명이 아니다 |

**exactly-once 나 "모든 장애에서 안전" 을 주장하지 않는다.** 아래 §8 이 검증하지 않은 범위다.

---

## 8. 미해결 사항과 검증하지 않은 것

### ★ 결정이 필요한 것 (ADR-009 §5)

| # | 내용 |
|---|---|
| C1 | 규칙 16 은 결과 기록을 TX2 로 고정한다. 선택 후보는 **성공 결과를 TX1 에** 기록한다. 규칙을 바꾸지 않았다 |
| C2 | I-18 보장 메커니즘 3 의 "CAS 로 재실행이 안전" 은 선점에서 반례가 재현됐다. 불변식 문구를 바꾸지 않았다 |
| C3 | 진행 중 응답의 HTTP 코드 — 규칙 21 은 409 를 좌석 경합으로 정의한다 |
| C4 | 리스 30초·보존 24시간은 근거 없는 가정이다 |

### 검증하지 않은 것

- **커밋 결과 불명**(COMMIT 중 연결 끊김) — 재현하지 않았다. 안전성은 구조적 논증(성공 기록이 같은 트랜잭션)에만 기댄다
- **실제 프로세스 종료** — `SimulatedCrash` 와 롤백으로 흉내 냈다. JVM kill·네트워크 분할은 없다
- **동시성 규모** — 같은 키 32스레드 1회. I-18 의 100회 동시 요청·100회 반복은 하지 않았다
- **다른 키·다른 사용자가 섞인 대규모 경합**, 키 행 추가로 늘어난 **TX1 길이와 잠금 보유 시간** — 측정하지 않았다
- **데드락 부재** — §3.2 의 논증과 테스트에서 관측되지 않았을 뿐이다
- **보존 기간 삭제와 진행 중 행의 경쟁** — 삭제 작업이 없다
- 업무 실패 중 `SALE_EVENT_SCOPE_REJECTED` 를 최종으로 저장하는 것이 맞는지 — 좌석이 삭제·이동되지 않는다는 **가정**에 기댄다

### 아직 없는 것

운영 테이블·저장소, 애플리케이션 연결, 인증 주체에서의 `userId`, `Idempotency-Key` 헤더 처리, HTTP 상태 매핑,
응답 직렬화, 메트릭(규칙 35), 감사 로그(규칙 32), 보존 기간 삭제 작업.

---

## 9. 후속 작업 (이번 완료와 구분)

1. **저장소** — ADR-009 결정 후 `reservation-infra` 에 운영 migration(`idempotency_key`)과 JDBC 저장소, 보존 기간 삭제
2. **애플리케이션 연결** — `HoldSeatsService` 앞단의 TX0/TX1/TX2 조정자와 메트릭
3. **인증·HTTP 경계** — 인증 주체 `userId`, 헤더 필수(400), 상태 코드(C3), 응답 직렬화

---

## 10. 재현 명령

```bash
./gradlew :apps:reservation-service:test --tests "*.idempotency.IdempotencyContractTest"
./gradlew :apps:reservation-service:test --tests "*.idempotency.HoldIdempotencyFailureWindowTest"
./gradlew check --rerun-tasks --max-workers=1
```

---

## 11. 전체 검증 기록

2026-10-11, `./gradlew check --rerun-tasks --max-workers=1` — `BUILD SUCCESSFUL`, 21 tasks executed.
`verifyArchitectureRules`(도메인 순수성·모듈 경계·버전 직접 명시 금지) 포함. `git diff HEAD --check` 통과.

현재 XML(`build/test-results/test`) 기준:

| 모듈 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `apps:queue-service` | 1 | 0 | 0 | 0 |
| `apps:reservation-service` | 228 | 0 | 0 | 0 |
| `modules:reservation-domain` | 202 | 0 | 0 | 0 |
| `modules:reservation-infra` | 560 | 0 | 0 | 0 |
| **합계** | **991** | **0** | **0** | **0** |

**테스트 수가 바뀐 이유** — TASK-002H-F 기록의 948개에서 +43. 전부 `apps:reservation-service` 의 신규
`IdempotencyContractTest`(23)와 `HoldIdempotencyFailureWindowTest`(20)다. 기존 테스트를 지우거나 제외하지 않았다.
§6 의 변이 실행(4건 실패)은 이 기록과 별개의 임시 실행이며, 위 결과는 원래 구현으로 돌린 것이다.

Docker 는 다른 프로젝트 컨테이너 5개가 떠 있는 상태(Docker 메모리 약 2GB, 2 CPU)에서 실행했고 OOM 은 없었다.
다른 컨테이너를 멈추거나 Docker 자원을 바꾸지 않았다.
