# TASK-002H-C — 단일 좌석 확정과 quota 감소의 같은 트랜잭션 연결

> 관련: [REQUIREMENTS.md](../REQUIREMENTS.md) P-2·P-4, [INVARIANTS.md](../INVARIANTS.md) I-8·I-12·I-15,
> [TASK-002B](TASK-002B-seat-confirm-race.md) — 확정 저장소,
> [TASK-002F](TASK-002F-transaction-boundary.md) — 트랜잭션 경계,
> [TASK-002G-B](TASK-002G-B-quota-lock-order.md) — 잠금 순서,
> [TASK-002G-C](TASK-002G-C-expiry-quota-attribution.md) — 감소량은 실제 변경 수,
> [TASK-002H-A](TASK-002H-A-hold-application-service.md), [TASK-002H-B](TASK-002H-B-release-application-service.md)

## 1. 목적과 범위

선점(2H-A)·자발적 해제(2H-B)에 이어 **단일 좌석 확정(`PAYING → SOLD`)** 을 quota 감소와
같은 트랜잭션으로 묶는다.

> ### ★ I-12 는 여전히 운영 활성화 전이다
>
> 연결된 것은 **선점·자발적 해제·확정 세 경로**다. **만료 경로는 여전히 quota 를 줄이지 않는다.**
> 홀드가 만료되어 스위퍼가 좌석을 회수해도 카운터는 그대로 남는다. 스위퍼 배선 자체가 없다.
>
> ### ★ I-15 를 완성한 것이 아니다
>
> 확정은 **호출자가 결제 승인을 확인했다는 전제**로 동작한다. `payment` 테이블도 PG 연동도 없고
> 승인 여부를 DB 조건으로 강제하지 않는다. "좌석 재고가 이 예약에 귀속됐다" 만 뜻한다.

**하지 않은 것** — 실제 결제 승인·PG 호출·결제 시작 서비스, 다좌석 예약 확정, REST API,
멱등키, 만료 quota 연동, 스케줄러, 감사 로그·메트릭, migration·운영 데이터 변경, backfill, 새 인덱스.

---

## 2. 확정 계약과 귀속 조회

| 항목 | 계약 |
|---|---|
| 입력 | `SeatId` + `HoldId` + `ReservationId`. 기존 `confirm` 과 같다. **사용자·회차를 받지 않는다** |
| 귀속 | 좌석 행의 `held_by` 와 운행편의 회차를 **서버가 읽는다.** UPDATE 가 `hold_id`·`held_by` 를 지우므로 **UPDATE 전**에 확보 |
| 귀속 조회 | `(id, hold_id, status='PAYING')` — 확정 UPDATE 의 `WHERE` 와 **같은 조건**. **잠금 없는 스냅숏** |
| 대상 없음 | `NOT_CONFIRMED`. PAYING 아님·다른 홀드·없는 좌석·이미 SOLD 를 구분하지 않는다. **다른 소유자의 quota 를 추측하거나 만들지 않는다** |
| 손상 데이터 | 조건에 맞는 행이 있는데 `held_by` 가 없으면 `HoldAttributionException`. **대상 없음과 구분**하며 쓰기 전에 거절 |
| 최종 판단 | 기존 조건부 UPDATE 의 `affected_rows`. `hold_id`·`PAYING` 조건 유지, **만료 시각 조건은 추가하지 않았다** |
| 감소량 | 확정에 성공한 **1석**. `NOT_CONFIRMED` 면 호출하지 않는다 |
| 정합성 오류 | 확정됐는데 행이 없거나 감소 거절 → `QuotaInconsistencyException` → **SOLD·`reservation_id`·홀드 정보 삭제까지 모두 롤백** |
| 재확정 | 이미 SOLD 면 `NOT_CONFIRMED`, quota 다시 안 줄임. **"최초 응답을 재생한 멱등 성공" 이 아니다** (규칙 17) |
| 반환값 | `REQUIRED` 참여이므로 **바깥 트랜잭션이 있으면 아직 최종 커밋이 아니다** |

### ★ 귀속 스냅숏과 최종 변경 대상이 같다는 근거

귀속은 `(id, hold_id, PAYING)` 으로 읽고, 확정은 **같은 조건**으로 갱신한다. 확정이 1 건을
바꿨다면 그 행은 여전히 같은 `hold_id` 를 가진 행이다. 그 행의 `held_by`·회차가 스냅숏과 같다는
것은 세 가지에 의존한다.

1. **`hold_id` 는 홀드마다 유일하고 재사용되지 않는다** — `HoldId.newId()`(UUID)
2. **`hold_id` 와 `held_by` 는 항상 함께 쓰이고 함께 지워진다** — I-8 전이 규칙. 선점이 둘을 함께
   쓰고 해제·만료·확정이 둘을 함께 지운다. `hold_id` 만 남기고 `held_by` 를 바꾸는 경로가 없다
3. **좌석의 운행편 소속과 운행편의 회차 소속은 변하지 않는다** — TASK-002G-E-A 계약

셋 중 하나가 깨지면 스냅숏은 다른 사람의 카운터를 가리킬 수 있다. 셋이 유지되는 한, 스냅숏 이후
상태가 바뀌면 UPDATE 가 0 건이 되어 감소하지 않으므로 stale 스냅숏 자체는 무해하다 (§5 stale 테스트).

---

## 3. 트랜잭션 경계와 잠금 순서

`ConfirmSeatService.confirm` 이 `@Transactional(REQUIRED)` 하나로 경계를 소유한다.

```
[트랜잭션]  1. resolvePayingSeat(seatId, holdId)   잠금 없는 조회 → (userId, saleEventId)
            2. quota.lockRow(회차, userId)          SELECT ... FOR UPDATE  ← quota 먼저
            3. seats.confirm(seatId, holdId, rid)  기존 조건부 UPDATE (hold_id · PAYING)
            4. NOT_CONFIRMED → 반환 (감소 없음)
               counter 없음 → QuotaInconsistencyException (SOLD 까지 롤백)
               quota.release(…, 1) → REJECTED → QuotaInconsistencyException (SOLD 까지 롤백)
```

**quota → seat.** 선점·해제와 같다. 1 이 잠금 없는 조회여야 하는 이유가 이것이다.
2 에서 행이 없어도 판단하지 않는다 — 확정이 실제로 일어났을 때만 정합성 문제가 된다.

---

## 4. 포트와 배선

| 포트 | 추가 | 소비자 |
|---|---|---|
| `SaleEventScopePort` | `resolvePayingSeat(SeatId, HoldId): Optional<HoldAttribution>` | 확정 |
| `SeatConfirmationPort` (신규) | `confirm(SeatId, HoldId, ReservationId): SeatConfirmationOutcome` | 확정 |
| `UserHoldQuotaPort` | 변경 없음 — 2H-B 의 `lockRow`·`release` 재사용 | 확정 |

- `SeatConfirmationOutcome` 을 infra → domain 으로 **옮겼다** (복제 없음). `SeatPaymentOutcome` 은 infra 에 남는다
- 신규 순수 Java 타입: `HoldAttribution(userId, saleEventId)`, `HoldAttributionException`
- **`startPayment` 는 포트에 노출하지 않았다.** 확정 유스케이스가 쓰지 않는다. 통합 테스트는 기존 저장소를 직접 써서 PAYING 을 준비한다
- `ReservationPersistenceConfig` 가 `SeatConfirmationPort` 빈을 추가. 선점·해제와 같은 DataSource·`DataSourceTransactionManager`
- 기존 저장소의 직접 호출 계약과 테스트는 유지. `implements`·`@Override` 만 붙였다

### 새 조회 SQL 의 실행 계획 — 새 인덱스 없음

`JdbcSaleEventScopeRepository.PAYING_SEAT_ATTRIBUTION_SQL`:

```sql
SELECT s.held_by, ts.sale_event_id
  FROM seat_inventory s
  STRAIGHT_JOIN train_schedule ts ON ts.id = s.schedule_id
 WHERE s.id = ? AND s.hold_id = ? AND s.status = 'PAYING'
```

EXPLAIN 어서션(`JdbcSaleEventScopeRepositoryTest`)이 고정하는 것: `s` 는 PRIMARY 사용·접근 유형 `const`,
`ts` 는 **PRIMARY 사용, 접근 유형은 `const` 또는 `eq_ref`** (좌석이 상수로 접히면 운행편도 상수로
평가될 수 있어 둘 다 허용한다). 어느 쪽이 나왔는지는 어서션이 기록하지 않으므로 여기서 단정하지 않는다.
PK 로 한 건씩이라 인덱스를 추가할 이유가 없고 추가하지 않았다.

---

## 5. 검증 결과

### 단위 — `ConfirmSeatServiceTest` (11개, DB 없음)

호출 순서 `resolvePayingSeat → lockRow → confirm → release(1)`, DB 에서 해석한 귀속 정보로 잠금·감소,
같은 seat/hold/reservation 전달, 대상 없음 → quota 미접근, 0 건 → 감소 미호출, 행 없음+0 건 → 오류 아님,
행 없음+확정 → 정합성 오류(`ensureRow` 미호출), 감소 거절 → 정합성 오류, 귀속 손상 → 쓰기 전 전파,
배선 실수 구분, null 거부.

### 통합 — `ConfirmSeatServiceIntegrationTest` (18개, Spring 빈 + MySQL 8.4)

선점은 `HoldSeatsService`, 결제 시작은 **기존 `startPayment` 저장소** 로 준비한다.

| 절 | 검증 | 픽스처 주의 |
|---|---|---|
| §1 정상 (3) | SOLD·`reservation_id`·홀드 정리·quota 감소 함께 반영, **여러 홀드·사용자·회차 중 해당 좌석의 quota 만 1 감소**, **만료 지난 PAYING 도 확정**(기존 계약) | 만료는 직접 SQL |
| §2 NOT_CONFIRMED (5) | 재확정(quota 재감소 없음·주인 불변), 다른 holdId, 없는 좌석(quota 행 미생성), HELD 상태, **stale 스냅숏**(남이 먼저 확정 → 0 건·감소 없음) | 남의 확정은 직접 SQL |
| §3 정합성 (4) | 행 없음 → PAYING 모든 필드 복구·행 미생성, 부족 → 복구·값 미보정, `held_by` 없음 → 쓰기 전 거절, 외부 롤백 → 확정·감소 복구 | 행 없음·부족·손상은 직접 SQL |
| §4 동시 (2) | 같은 좌석 동시 확정 → CONFIRMED+NOT_CONFIRMED·quota 한 번, **확정·`ReleaseHoldService` 경쟁** → 카운터 0 = 활성 0 | — |
| §5 RED 대조군 (3) | 정합성 오류를 삼키면 행 부재 → SOLD 커밋·카운터 없음, 부족 → SOLD 커밋·카운터 0; 올바른 구현은 PAYING 복구 | 직접 SQL |
| §6 프록시 (1) | 손으로 조립하면 `IllegalStateException` | — |

### infra — `JdbcSaleEventScopeRepositoryTest` §결제 중 좌석 (4개)

점유자·회차 반환, 대상 없음(다른 홀드·HELD·없는 좌석) → 빈 값, `held_by` 없음 → 예외, EXPLAIN.

### 주장의 층

| 층 | 검증 |
|---|---|
| **결정적 재현** | §2 stale 스냅숏 — 바깥 트랜잭션에서 비잠금 읽기로 REPEATABLE READ 스냅숏 고정 → 별도 autocommit 커넥션으로 SOLD 커밋 → 서비스 호출. 순서가 코드로 고정되어 매번 0 건·감소 없음 |
| **결정적 재현** | §5 대조군 — 단일 스레드로 커밋 후 상태 실측 |
| **동시 실행의 보조 검증** | §4 — `CountDownLatch` 는 출발만 맞춘다. 결과의 합(CONFIRMED+NOT_CONFIRMED, 카운터 0)이 일정한 것은 이 실행의 관측이며 모든 교차 순서를 시험한 것이 아니다. 확정·해제 경쟁은 두 분기(확정 먼저: a SOLD·b 해제 / 해제 먼저: 둘 다 해제 — PAYING 도 해제 대상)를 모두 받아들이고 불변식만 확인한다. 어느 분기를 탔는지는 기록하지 않았다 |
| **구조적 논증** | quota → seat 순서로 같은 사용자·회차의 트랜잭션이 quota 행에서 직렬화되고, 감소가 항상 UPDATE 의 `affected_rows` 를 따르므로 이중 감소가 없다 |

---

## 6. ★ 먼저 실패시킨 기록 (규칙 27)

운영 코드를 임시로 바꿔 관측한 뒤 원복했고, 원복 후 파일이 바이트 동일함을 확인했다.
**RED A·C 는 당시 17개짜리**(§2 stale 추가 전) 통합 테스트로, **RED B 는 stale 추가 후 18개로
재실행**했다. 아래에 각각 적는다.

### RED A — 정합성 오류를 삼키고 CONFIRMED 반환 (17개 기준)

| 항목 | 값 |
|---|---|
| 실패 | **3개** — §3 행 없음·부족, §5 올바른 구현 |
| 관측 범위 | **예외 미발생을 탐지했다.** 세 테스트 모두 `assertThatThrownBy` 에서 먼저 실패하므로 커밋 후 상태 어서션에는 도달하지 않았다 |
| 커밋 후 상태 | §5 대조군이 별도로 실측: 행 부재 → SOLD 커밋·카운터 여전히 없음, 부족 → SOLD 커밋·카운터 0 그대로 |

### RED B — `NOT_CONFIRMED` 여도 감소 호출

| 항목 | 값 |
|---|---|
| 1차 실행 (17개) | §4 동시 2개만 실패 — 늦은 쪽이 0 건인데 `release(1)` 을 불러 `REJECTED` → 예외 |
| 왜 결정적 테스트가 못 잡았나 | §2 재확정·다른 holdId·HELD 는 **귀속 조회가 빈 값**이라 `lockRow` 전에 끝난다. 이 가드가 필요한 창은 "스냅숏은 PAYING 인데 UPDATE 는 0 건" 뿐이고, 그것은 경쟁에서만 생긴다 |
| 보완 | §2 에 **stale 스냅숏 테스트**를 추가해 그 창을 결정적으로 만들었다 (2H-B §3 과 같은 방법) |
| 2차 실행 (18개) | **3개** 실패 — §2 stale + §4 동시 2개. 이제 결정적으로도 잡힌다 |

### RED C — `@Transactional` 제거 (17개 기준)

| 항목 | 값 |
|---|---|
| 실패 | **9개** — §1 3개, §2 재확정 1개, §3 행 없음·부족 2개, §4 2개, §5 올바른 구현 1개 |
| 원인 | 전부 `lockRow` 의 참여 검사 `IllegalStateException` |

살아남은 8개: §2 다른 holdId·없는 좌석·HELD 는 **귀속 조회가 빈 값**이라 `lockRow` 전에 끝난다.
§3 `held_by` 없음은 조회가 `lockRow` 전에 예외를 던지고 테스트가 그것을 기대한다. §3 외부 롤백과
§5 대조군 2개는 테스트가 **외부 `TransactionTemplate` 으로 경계를 열어** 참여한다. §6 은 거절을 기대한다.
§2 재확정은 **준비 단계의 첫 확정**에서 실패했다. 당시 없던 §2 stale 은 포함되지 않는다.

---

## 7. 남은 위험

### ★ 운영 활성화 전에 반드시 필요한 것

| 항목 | 상태 |
|---|---|
| **만료 경로의 quota 감소** | **없다.** 스위퍼 배선도 없다 (2G-F 다중 실행 계약 유지 예정) |
| **I-15 결제 승인 검증** | **없다.** 확정은 호출자의 전제로 동작한다 |
| **다좌석 예약 확정** | 없다. 단일 좌석 `confirm` 계약을 유지했다 |
| **REST API·멱등키** | 없다. 재확정 `NOT_CONFIRMED` 는 CAS 결과이지 최초 응답 재생이 아니다 |
| **감사 로그**(규칙 32) | 없다. 확정 UPDATE 가 지우는 홀드 정보는 기록되지 않는다 |
| **backfill** | 절차만 있다. 거치지 않은 PAYING 은 확정이 `QuotaInconsistencyException` 으로 거절된다 — 의도된 동작 |

### 검증하지 않은 것

- 확정과 **만료 스위퍼**의 경쟁에서의 카운터 결과 — 만료가 미연동이라 지금은 의미 없다 (좌석 상태 경쟁 자체는 TASK-002D 가 검증)
- 확정과 **선점**의 직접 경쟁 — 같은 좌석은 상태가 달라 경쟁하지 않고, 같은 사용자의 quota 행에서 직렬화된다는 구조적 논증만 있다
- §4 확정·해제 경쟁이 실제로 어느 분기를 탔는지
- `held_by` 는 있는데 `hold_id` 가 없는 반대 손상 — 조회 조건에 `hold_id` 가 있어 대상 없음으로 흡수된다. 별도 시험하지 않았다
- 잠금 보유 시간, 데드락 부재(관측되지 않았을 뿐)

---

## 8. 재현 명령

```bash
./gradlew :apps:reservation-service:test --tests "*ConfirmSeatServiceTest"
./gradlew :apps:reservation-service:test --tests "*ConfirmSeatServiceIntegrationTest"
./gradlew :modules:reservation-infra:test --tests "*JdbcSaleEventScopeRepositoryTest"
./gradlew check --rerun-tasks
```
