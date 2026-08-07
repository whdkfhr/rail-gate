# 실험 — 결제 시작과 좌석 확정 CAS

> TASK-002B · 2026-08-02
> 관련: [INVARIANTS.md](../INVARIANTS.md) I-8·I-11·I-15, [CLAUDE.md](../../CLAUDE.md) 규칙 1·4·7·32
> 선행: [TASK-002A-seat-hold-race.md](TASK-002A-seat-hold-race.md)

## 목적

Task 2A가 `AVAILABLE → HELD` 경쟁을 막았다. 이 문서는 그 뒤 두 전이를 다룬다.

```
Task 2A          AVAILABLE ──hold──▶ HELD
Task 2B (여기)                       HELD ──startPayment──▶ PAYING ──confirm──▶ SOLD
```

이로써 **`seat_inventory` 단일 좌석 수준의 I-8 방어가 완성됐다.** 세 전이가 모두 조건부 UPDATE로 강제된다.

다만 예매 흐름 전체의 정합성을 위해 별도의 불변식과 기능이 남아 있다 (§6). **I-9·I-11·I-12·I-15는 I-8의 하위 조건이나 완성 조건이 아니라 각각 독립된 불변식**이며, I-8이 미완성이라는 뜻이 아니다.

---

## 1. 두 개의 조건부 UPDATE

### HELD → PAYING

```sql
UPDATE seat_inventory
   SET status     = 'PAYING',
       expires_at = GREATEST(expires_at, DATE_ADD(NOW(3), INTERVAL ? SECOND)),
       version    = version + 1
 WHERE id         = ?
   AND hold_id    = ?
   AND status     = 'HELD'
   AND expires_at > NOW(3)
```

| 조건 | 막는 것 |
|---|---|
| `hold_id = ?` | 남의 홀드로 결제 시작 |
| `status = 'HELD'` | 이미 결제 중이거나 팔린 좌석, 빈 좌석 |
| `expires_at > NOW(3)` | **이미 만료된 홀드로 결제 시작** |

`expires_at > NOW(3)`가 **만료 방어의 유일한 지점**이다. 도메인은 현재 시각을 모르므로(CLAUDE.md 규칙 7) 이 검사를 대신할 수 없다. `Seat.startPayment()`는 두 시각의 상대 관계만 다룬다.

`GREATEST`는 만료가 앞당겨지는 것을 구조적으로 막는다(P-4). 단순 대입이면 결제 창(5분)이 홀드 잔여 시간보다 짧은 순간에 오히려 남은 시간을 깎아, 결제 도중 스위퍼가 좌석을 회수하게 된다.

`hold_id`·`held_by`·`held_at`은 건드리지 않는다. `PAYING`도 여전히 임시 점유이며 확정과 해제가 소유권을 확인해야 한다.

### PAYING → SOLD

```sql
UPDATE seat_inventory
   SET status         = 'SOLD',
       reservation_id = ?,
       hold_id        = NULL,
       held_by        = NULL,
       held_at        = NULL,
       expires_at     = NULL,
       version        = version + 1
 WHERE id      = ?
   AND hold_id = ?
   AND status  = 'PAYING'
```

`WHERE status = 'PAYING'` 때문에 이미 `SOLD`인 행은 매칭되지 않는다. **확정된 좌석의 `reservation_id`는 이후 어떤 확정 요청으로도 덮어써지지 않는다.**

`WHERE`가 기존 `hold_id`를 읽고 `SET`이 그것을 비운다. 소유권 검사가 자연히 상태 변경보다 먼저 끝난다.

---

## 2. 왜 모든 전이에 `hold_id` 조건이 필요한가

좌석 id만으로 전이하면 **남의 홀드를 진행시킬 수 있다** (CLAUDE.md 규칙 4).

```
t=0     A 가 좌석 12A 선점 (hold_id = H_A)
t=+5m   A 의 홀드 만료 → 스위퍼가 회수 → AVAILABLE
t=+5m1s B 가 같은 좌석 선점 (hold_id = H_B)
t=+5m2s A 의 지연된 확정 요청 도착

  hold_id 조건 없음 → B 의 좌석이 A 의 예약으로 확정된다
  hold_id 조건 있음 → affected_rows = 0
```

홀드 식별자는 "지금 이 좌석을 누가 잡고 있는가"에 대한 유일한 답이며, 모든 전이는 그 답을 확인한 뒤에만 일어난다. 이는 I-11이 요구하는 것과 같은 조건이다.

---

## 3. 왜 `affected_rows`가 유일한 성공 판정 기준인가

```java
int affectedRows = jdbc.update(CONFIRM_SQL, ...);
return affectedRows == 1 ? CONFIRMED : NOT_CONFIRMED;   // 재조회 없음
```

UPDATE 직후 `SELECT`로 확인하면 **또 하나의 창이 생긴다.** 그 사이 좌석이 다시 바뀌어도 알 수 없고, 오히려 "성공했는데 남의 상태를 확인"하는 결과가 나온다.

갱신을 수행한 그 문장이 몇 행을 바꿨는지가 **그 시점의 유일하게 신뢰할 수 있는 사실**이다. 실패 원인(상태 불일치 / 홀드 불일치 / 만료 / 좌석 없음)을 구분하려면 추가 조회가 필요하지만, 호출자에게 필요한 것은 "내 것이 되었는가" 하나다.

---

## 4. 측정 결과

환경은 Task 2A와 동일하다. MySQL 8.4 (Testcontainers), HikariCP max 64, `innodb_lock_wait_timeout = 3`, Java 21 가상 스레드. 동시 출발은 `CountDownLatch`이며 `Thread.sleep`을 쓰지 않는다.

### 1,000개 예약이 같은 좌석을 동시 확정

서로 다른 `ReservationId` 1,000개가 **동일한 `HoldId`** 로 경쟁한다.

| 지표 | 값 |
|---|---|
| 확정 성공 | **1건** |
| 확정 실패 | **999건** |
| 시스템 오류 / lock timeout / deadlock | **0건** |
| 소요 시간 | **0.144초** |
| DB `reservation_id` | 성공 응답을 받은 승자와 **일치** |
| 확정 후 `hold_id`·`held_by`·`held_at`·`expires_at` | 전부 NULL |
| 좌석 행 수 | 1 |

### 1,000개 요청이 같은 홀드로 동시 결제 시작

| 지표 | 값 |
|---|---|
| 성공 | **1건** |
| 실패 | **999건** |
| 시스템 오류 | **0건** |
| 소요 시간 | **0.143초** |

### 기능 검증

`reservation-infra` **66 tests, 0 failures**. 주요 항목:

| 검증 | 결과 |
|---|---|
| 만료된 HELD의 결제 시작 | 거부 (`expires_at > NOW(3)`) |
| 만료 시각과 정확히 같은 순간 | 거부 (경계 배타적) |
| 기존 만료가 더 늦음 (1시간) | 그대로 유지 |
| 기존 만료가 더 이름 (30초) | 5분까지 연장 |
| 결제 시작 후 `hold_id`·`held_by`·`held_at` | 유지 |
| `HELD`에서 바로 확정 | 거부 |
| 다른 `hold_id`로 확정 | 거부, `PAYING` 유지 |
| `SOLD` 재확정 | 0건, `reservation_id` 불변 |
| 확정된 행이 스위퍼 회수 조건에 걸리는가 | 걸리지 않음 |

---

## 5. seat_inventory 수준에서 I-8이 어떻게 완성되는가

Task 2A §4의 3단 논증(행이 하나 / 행 잠금 / current read)이 세 전이에 동일하게 적용된다. 여기에 전이별 조건이 더해진다.

```
AVAILABLE ──▶ HELD     WHERE id=? AND status='AVAILABLE'
HELD      ──▶ PAYING   WHERE id=? AND hold_id=? AND status='HELD' AND expires_at>NOW(3)
PAYING    ──▶ SOLD     WHERE id=? AND hold_id=? AND status='PAYING'
```

각 전이의 `WHERE`에 **출발 상태가 명시**되어 있으므로 전이표에 없는 경로는 `affected_rows = 0`이 된다. `SOLD`를 출발점으로 하는 조건이 아예 없어 터미널 상태가 구조적으로 보장된다.

`reservation_id`는 `PAYING → SOLD` 한 곳에서만 쓰이고, 그 전이가 `status='PAYING'`을 요구하므로 **좌석당 최대 한 번만 기록된다.**

---

## 6. ⚠️ 별도로 남아 있는 불변식과 기능

이 문서가 보인 것은 **좌석 재고 행 수준의 전이**이며, 그 범위의 I-8 방어는 완성됐다.

아래 항목은 **I-8의 완성 조건이 아니라 예매 흐름 전체의 정합성을 위해 각각 독립적으로 필요한 것**이다. 하나가 빠져도 "좌석 하나가 두 예약에 확정되는" 일은 일어나지 않는다.

### I-15 결제 승인 검증 — 미구현

`confirm()`은 **결제가 승인됐는지 확인하지 않는다.** `payment` 테이블도 PG 연동도 없다. 확정은 "좌석 재고가 이 예약에 귀속됐다"만 뜻하며 **돈이 승인됐다는 뜻이 아니다.**

I-15가 요구하는 것은 확정 트랜잭션이 `payment.status='AUTHORIZED'`를 조건으로 포함하는 것이다. 현재는 호출자가 승인을 확인한 뒤 부르는 것을 **전제할 뿐 강제하지 않는다.** 그래서 `confirm()`은 REST API에 노출하지 않는다.

### I-11 만료 스위퍼와의 경쟁 — 미검증

스위퍼가 아직 없다. 확정(`WHERE hold_id=? AND status='PAYING'`)과 회수(`WHERE status IN ('HELD','PAYING') AND expires_at<=NOW(3)`)가 **같은 행을 동시에 노리는 상황**은 스위퍼 구현과 함께 검증해야 한다(INVARIANTS.md의 `T-5`).

*(TASK-002D에서 스위퍼가 구현되어 이 경쟁을 검증했다 — [TASK-002D-seat-expiry-sweeper.md](TASK-002D-seat-expiry-sweeper.md). 위 서술은 TASK-002B 시점 기준으로 유지한다.)*

현재 확정이 `expires_at`을 NULL로 비우고 `SOLD`가 스위퍼의 상태 술어에서 제외된다는 것까지는 확인했지만, **두 CAS를 동시 출발시킨 테스트는 없다.**

### 규칙 32 감사 로그 — 미구현 ★

CLAUDE.md 규칙 32는 모든 좌석 상태 전이를 `seat_state_log`에 `actor`, `reason`, `traceId`와 함께 기록하도록 요구한다. **그 테이블도 기록 로직도 존재하지 않는다.**

특히 `PAYING → SOLD`는 `hold_id`·`held_by`·`held_at`을 NULL로 지우므로, **직전 홀드 정보가 되돌릴 수 없이 소실된다.** 지금은 "누가 이 좌석을 확정했는가"를 사후에 재구성할 방법이 없다.

애플리케이션 서비스가 없어 `actor`/`reason`/`traceId`를 전달할 수 없는 상태이며, **임의의 값을 채워 넣으면 감사 로그의 목적 자체가 무너지므로 구현하지 않았다.** 앱 계층이 생기는 시점에 함께 만든다.

### 멱등성 — 이 계층의 책임이 아님

`SOLD` 재확정이 0건인 것은 **저장소 CAS의 결과일 뿐 API 멱등성이 아니다.** 재요청에 최초 응답을 그대로 돌려주는 것은 멱등성 키 인프라의 책임이다(CLAUDE.md 규칙 17). 여기서 보장하는 것은 "이미 팔린 좌석의 주인이 바뀌지 않는다" 하나다.

### I-12 — 후속 Task (I-9는 TASK-002C에서 구현됨)

| 불변식 | 필요한 것 |
|---|---|
| I-9 다좌석 전부-또는-전무 | 단일 벌크 UPDATE + `affected_rows == 요청 좌석 수` 검증, `FORCE INDEX (PRIMARY)`, id 정렬 → **[TASK-002C](TASK-002C-multi-seat-atomic-hold.md)에서 구현됨** |
| I-12 1인당 좌석 상한 | `user_hold_quota` 카운터 행의 조건부 UPDATE. 감소 경로(SOLD/release/expiry)가 없으면 카운터가 영구히 남으므로 함께 구현해야 한다 |

### 스키마 주석의 정정 불가 항목

`V1__seat_inventory.sql`의 헤더 주석은 확정 단계를 "후속 Task"로 설명한다. **이 파일은 이미 main에 병합되어 적용된 마이그레이션이므로 수정하지 않는다** — 내용을 고치면 Flyway checksum이 바뀌어 기존 환경의 마이그레이션 검증이 실패한다. 실제 구현 상태는 이 문서가 기준이다.

---

## 7. 재현 명령

```bash
./gradlew :modules:reservation-infra:test --tests "*SeatPaymentConcurrencyTest"
./gradlew :modules:reservation-infra:test --tests "*JdbcSeatPaymentRepositoryTest"
```

Docker가 실행 중이어야 한다.
