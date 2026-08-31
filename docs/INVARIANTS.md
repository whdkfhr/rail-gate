# RailGate — 시스템 불변식

> 상태: 초안 v0.1 (2026-07-28)
>
> **이 문서가 프로젝트의 핵심 산출물이다.**
> 기능은 추가·삭제되지만 불변식은 깨지면 안 된다.
> 모든 설계 결정은 "어떤 불변식을 지키기 위한 것인가"로 설명할 수 있어야 한다.

각 불변식은 다음 3가지를 반드시 명시한다.

- **보장 메커니즘** — 무엇이 이것을 지키는가
- **깨지는 조건** — 어떻게 하면 깨지는가 (방어를 제거했을 때)
- **검증 방법** — 지켜졌음을 어떻게 확인하는가

---

## 대기열

### I-1. 동일 이벤트에서 동일 사용자는 동시에 최대 1개의 유효 대기열 엔트리를 가진다

- **보장 메커니즘** — 등록 전체가 **단일 Lua 스크립트**. `HGET entry` → 없으면 `INCR seq` + `HSET` + `ZADD` 가 원자적으로 실행된다. ZSET member 를 `userId` 로 잡아 중복 ZADD 가 행을 늘리지 않는다.
- **깨지는 조건** — 등록을 애플리케이션에서 여러 Redis 명령으로 쪼개면, `HGET` 과 `HSET` 사이에 다른 요청이 끼어들어 순번이 중복 발급된다.
- **검증 방법** — `T-1`: 동일 사용자가 등록 API 를 100번 동시 호출 → `seq` 카운터가 정확히 1 증가, 100개 응답의 순번이 전부 동일, ZSET member 1개.

### I-2. 유효 엔트리 보유 중 재등록 요청은 기존 순번을 반환한다

- **보장 메커니즘** — I-1 과 동일한 Lua 스크립트. `(userId, eventId)` 가 자연 멱등키이므로 별도 멱등키 헤더가 필요 없다.
- **깨지는 조건** — 새로고침마다 새 순번을 발급하면 사용자가 영원히 입장하지 못한다.
- **검증 방법** — `T-1` 에 포함. 추가로 순차 재요청 10회 → 순번 불변.

### I-3. 발급된 순번은 단조 증가하며 재사용·중복 발급되지 않는다

- **보장 메커니즘** — Redis `INCR`. **타임스탬프를 순번으로 쓰지 않는다** (밀리초 동률 대량 발생, 클럭 왜곡에 취약, 공정성을 증명할 수 없음).
- **깨지는 조건** — 클라이언트 시각이나 애플리케이션 시각을 score 로 쓰는 경우.
- **검증 방법** — 등록 10,000건 후 발급된 순번 집합의 크기 == 10,000, 최댓값 == 10,000.

### I-4. 입장 완료선(`admitted_seq`)은 감소하지 않는다

- **보장 메커니즘** — 승격 Lua 안에서 `if newMax > current then SET` 형태로만 갱신.
- **깨지는 조건** — 스케줄러 2대가 서로 다른 배치를 처리하며 각자 `SET` 하면 값이 뒤로 갈 수 있다.
- **검증 방법** — 스케줄러 3대 동시 실행 중 `admitted_seq` 를 100ms 주기로 샘플링 → 감소 구간 0건.

> ⚠️ **주의:** `admitted_seq` 는 "승격한 **인원 수**" 가 아니라 "마지막으로 승격된 **순번의 최댓값**" 이다.
> 인원 수로 두면 취소·만료로 생긴 구멍만큼 순번 추정치가 **영구히 부정확**해진다.
> 취소는 단조 누적되므로 이 오차는 스스로 보정되지 않는다.

### I-5. Active 사용자 수는 설정된 상한을 초과하지 않는다

- **보장 메커니즘** — 승격 인원 `N` 을 **Lua 스크립트 내부에서 계산**한다.
  ```lua
  local current = redis.call('ZCOUNT', activeKey, now, '+inf')  -- 만료분 자동 제외
  local n = math.min(target - current, maxBatch)
  ```
- **깨지는 조건** — `ZCARD` 를 Java 에서 읽고 `N` 을 계산해 Lua 에 넘기면, check-then-act 가 두 번의 왕복으로 쪼개져 스케줄러 2대가 각각 상한까지 채운다. **분산 락은 이것을 막지 못한다** (GC pause, 네트워크 분할).
- **검증 방법** — `T-7`: 스케줄러 3대를 **락 없이** 30초 동시 실행하며 `ZCOUNT active` 를 100ms 주기 샘플링 → 최댓값이 target 을 절대 초과하지 않을 것.

### I-6. 입장 토큰은 특정 `(userId, eventId)` 에 바인딩되며 양도할 수 없다

- **보장 메커니즘** — JWT(HS256)의 `sub`, `evt` 클레임을 요청 주체·대상과 대조. 서명 검증만으로는 부족하다.
- **깨지는 조건** — 서명만 확인하고 `sub` 를 대조하지 않으면, 유출된 토큰을 아무나 쓸 수 있다.
- **검증 방법** — 사용자 A 의 토큰으로 B 가 좌석 API 호출 → 403.

### I-7. 입장 권한이 없는 사용자는 좌석 예매 API를 사용할 수 없다

- **보장 메커니즘** — `reservation-service` 의 필터에서 토큰 검증. **queue-service 로의 네트워크 호출 없이** 서명만으로 판정한다.
- **깨지는 조건** — 검증 실패 시 통과시키는 fail-open 정책. Redis 장애 시 전원 입장 → MySQL 붕괴.
- **검증 방법** — 토큰 없이 / 만료 토큰으로 좌석 API 호출 → 401. `T-8`: Redis 중지 중에도 무효 토큰이 차단되는지.

---

## 좌석 · 예약

### I-8. 하나의 좌석은 최종적으로 한 예약에만 확정된다 ★ 최우선

- **보장 메커니즘** — 3겹.
  1. `seat_inventory` 에 좌석당 **물리적으로 행이 하나** (`UNIQUE(schedule_id, seat_no)`)
  2. 모든 상태 전이가 **조건부 UPDATE**. `affected_rows` 가 유일한 성공 판정 기준
  3. InnoDB 행 잠금이 동시 UPDATE 를 직렬화
- **깨지는 조건** — `SELECT` 로 상태를 확인한 뒤 `UPDATE` 하면(TOCTOU) 검사와 갱신 사이에 다른 트랜잭션이 끼어든다. Redis 분산 락만으로 대체하면 락 만료 후 임계 구역이 계속 실행되어 깨진다.
- **검증 방법**
  - `T-2`: 1,000명이 하나의 좌석 동시 선점 → 성공 정확히 1건, 409 정확히 999건
  - 검증 쿼리 `V-2` (아래) 0행
  - **Phase 6 실험 C**: DB 조건부 UPDATE 를 제거하고 Redis 락만 사용 → 초과 예매가 실제로 발생함을 재현

### I-9. 여러 좌석 예약은 전부 선점되거나 전부 실패한다 (부분 성공 없음)

- **보장 메커니즘** — 단일 벌크 UPDATE + `affected_rows` 검증.
  ```sql
  UPDATE seat_inventory FORCE INDEX (PRIMARY)
     SET status='HELD', hold_id=:h, ...
   WHERE id IN (:sortedIds) AND status='AVAILABLE';
  -- affected_rows != 요청 좌석 수  →  ROLLBACK
  ```
- **깨지는 조건** — 좌석별로 반복문을 돌며 개별 UPDATE 하면 중간 실패 시 부분 선점이 남는다.
- **검증 방법** — `T-3`: 여러 사용자가 겹치는 2~4석을 서로 다른 순서로 선점 → `HELD` 좌석 수가 항상 성공 예약의 좌석 수와 정확히 일치.

### I-10. 선점 만료된 좌석은 다시 예약 가능 상태가 된다

- **보장 메커니즘** — Hold Expiry Sweeper 가 30초 주기로 `expires_at <= NOW(3)` 인 행을 조건부 UPDATE.
- **깨지는 조건** — 스위퍼가 없거나, 애플리케이션 시각으로 판정하면 인스턴스 간 클럭 드리프트로 판정이 갈린다.
- **검증 방법** — 만료 시각 경과 후 60초 내 `status='AVAILABLE'`. 검증 쿼리로 `expires_at < NOW() - 60s AND status='HELD'` 인 행 0개.

### I-11. 선점 만료 처리는 이미 확정된 예약의 좌석을 절대 해제하지 않는다 ★

- **보장 메커니즘** — 양쪽이 모두 CAS 이고, 같은 행이므로 InnoDB 행 잠금이 직렬화한다.
  - 확정: `WHERE hold_id=:h AND status='PAYING'`
  - 스위퍼: `WHERE status IN ('HELD','PAYING') AND expires_at <= NOW(3)` — **`SOLD` 를 명시적으로 제외**
- **깨지는 조건** — 스위퍼가 `WHERE expires_at <= NOW()` 만 보고 상태를 확인하지 않으면 확정된 좌석을 풀어버린다. 확정 쿼리에 `hold_id` 조건이 없으면 남의 홀드를 확정한다.
- **검증 방법** — `T-5`: 스위퍼와 확정 UPDATE 를 `CountDownLatch` 로 동시 출발 → 좌석이 `SOLD` 또는 `AVAILABLE` 중 하나로 확정되고 **중간 상태 없음**. 돈이 나갔는데 좌석이 없는 경우 0건.

### I-12. 한 사용자는 동일 판매 이벤트에서 4개를 초과하는 활성 선점을 가질 수 없다

> **현재 상태 — 계약 결정과 테스트 전용 검증 단계다. 운영에 구현되지 않았다.**
> `user_hold_quota` 테이블·운영 저장소·애플리케이션 서비스가 아직 없다.
> 범위를 정하는 `SaleEvent`·`TrainSchedule` 은
> [TASK-002G-E-A](experiments/TASK-002G-E-A-sale-event-domain.md) 에서 **순수 도메인 모델로만**
> 구현됐다. 운행편의 회차 소속에 대해 도메인이 보장하는 것은 **객체 인스턴스의 불변**과
> **공개 재배정 API 부재**뿐이다. 같은 `train_schedule.id` 의 `sale_event_id` 가 갱신되는 것은
> **아직 막지 못한다.** 방어 수단은 [TASK-002G-E-B1](experiments/TASK-002G-E-B1-schema-decision.md) 이 비교해 골랐고
> ( FK + 조건부 트리거 ), **적용은 Task 2G-E-B2** 다.
> 같은 문서가 좌석에서 회차를 얻는 경로를 **정규화 조인**으로 확정했다 —
> `seat_inventory` 에 `sale_event_id` 를 비정규화하지 않는다.

- **범위** — `sale_event_id` 단위. 같은 판매 이벤트의 **여러 열차 운행편을 합산**한다.
  서로 다른 판매 이벤트의 quota 는 독립이다. `SOLD`·`AVAILABLE` 은 활성 선점이 아니다.
  `schedule_id` 를 범위로 쓰면 운행편만 바꿔 상한을 우회할 수 있다
  ([TASK-002G-D](experiments/TASK-002G-D-quota-scope-contract.md) 에서 재현).
- **보장 메커니즘** — `user_hold_quota` 카운터 행에 **조건부 UPDATE**.
  ```sql
  UPDATE user_hold_quota SET held_seats = held_seats + :n
   WHERE sale_event_id = :saleEventId
     AND user_id       = :userId
     AND held_seats + :n <= 4;
  -- affected = 0 → 429 QUOTA_EXCEEDED
  ```
- **PRIMARY KEY 와 잠금 순서** — `(sale_event_id, user_id)`.
  여러 quota 행을 잠그는 경로(만료 배치)는 그 키의 **오름차순**으로 잠근 뒤 좌석을 갱신한다
  (quota → seat, [TASK-002G-B](experiments/TASK-002G-B-quota-lock-order.md)).
- **깨지는 조건** — `SELECT COUNT(*) WHERE held_by=:userId` 로 검증하면 **다른 트랜잭션의 미커밋 홀드가 보이지 않는다.** 같은 사용자가 3석 요청 2개를 동시에 보내면 둘 다 "내 것 3석"만 보고 통과해 6석을 점유한다.
  범위를 운행편으로 잡아도 같은 결과가 된다 — 카운터 행 자체가 분리되기 때문이다.
- **검증 방법** — 동일 사용자가 3석 요청 2개를 동시 전송 → 하나만 201, 하나는 429. 실제 `HELD` 좌석 수 ≤ 4.
  같은 판매 이벤트의 **서로 다른 운행편**으로 보내도 결과가 같아야 한다.

### I-13. 판매된 좌석 수 ≤ 해당 스케줄의 총 좌석 수

- **보장 메커니즘** — I-8 의 결과로 구조적으로 따라온다. 좌석당 행이 하나이므로 `SOLD` 행 수가 총 좌석 수를 넘을 수 없다.
- **깨지는 조건** — 좌석 재고를 카운터로만 관리하고 개별 행을 두지 않는 설계.
- **검증 방법** — 검증 쿼리 `V-1` 0행.

### I-14. 상태 전이는 정의된 전이표만 따르며 터미널 상태에서 역행하지 않는다

- **보장 메커니즘** — 도메인 모듈(`reservation-domain`)의 순수 Java 상태 머신이 전이를 검증. 조건부 UPDATE 의 `WHERE status=...` 가 DB 레벨 2차 방어.
- **깨지는 조건** — 상태를 무조건 덮어쓰는 UPDATE.
- **검증 방법** — 상태 머신 단위 테스트(전체 전이 조합 전수). 검증 쿼리 `V-5`.

---

## 결제

### I-15. 결제 승인이 검증된 예약만 확정 상태가 된다

- **보장 메커니즘** — 확정 트랜잭션이 `payment.status='AUTHORIZED'` 를 조건으로 포함.
- **깨지는 조건** — PG 응답을 신뢰하지 않고 낙관적으로 확정하는 경우.
- **검증 방법** — 검증 쿼리 `V-6` 0행.

### I-16. 확정된 예약의 결제 금액 = 예약 좌석 요금의 합

- **보장 메커니즘** — 확정 시점에 좌석 요금 합계를 재계산해 대조. 클라이언트가 보낸 금액을 신뢰하지 않는다.
- **깨지는 조건** — 요금이 좌석 선택 후 변경되었는데 재검증하지 않는 경우.
- **검증 방법** — 검증 쿼리 `V-4` 0행.

### I-17. 좌석 확정에 실패한 결제는 capture되지 않거나 반드시 환불된다

- **보장 메커니즘** — **capture 는 좌석 확정 성공 이후에만 호출한다.** 확정 실패 시 `void`(승인 취소)로 끝나므로 실제 돈이 움직이지 않는다.
- **깨지는 조건** — authorize 직후 capture 하면, 좌석을 못 얻었을 때 이미 매입된 돈을 환불해야 한다(보상 트랜잭션 필요).
- **검증 방법** — `T-5`. Mock PG 의 capture 호출 카운터로 검증.

---

## 멱등성 · 장애

### I-18. 동일 멱등키의 요청은 상태를 한 번만 변경하고, 이후 요청은 최초 결과를 반환한다

- **보장 메커니즘** — 3단계.
  1. `idempotency_key` 테이블 PK 로 중복 감지
  2. **리스(lease) 방식** — `IN_PROGRESS` 행이 크래시로 영구히 잠기지 않게, 리스 만료 후 다음 요청이 인수
  3. **실제 멱등성은 멱등키 테이블이 아니라 CAS 에서 나온다.** 재실행되어도 조건부 UPDATE 가 `affected=0` 을 반환하므로 안전하다
- **깨지는 조건**
  - 멱등키를 **선택 헤더**로 두면 없는 것과 같다 → **누락 시 400 으로 거부**
  - 멱등키 INSERT 와 본 작업을 한 트랜잭션에 넣으면 중복 키 예외 후 기존 응답을 읽을 수 없어 500 이 나간다 → **3단 트랜잭션 경계 필수**
  - `IN_PROGRESS` 에 리스가 없으면 크래시 후 영구히 409
- **검증 방법** — `T-4`: 선점 성공 후 응답 유실 및 동일 키 재요청 → 두 응답이 바이트 단위로 동일, 좌석 상태 변경 1회. 동일 키 100회 동시 요청 → **500 이 단 1건도 없을 것.**

### I-19. Kafka 메시지가 중복 전달되어도 중복 예약·중복 결제가 발생하지 않는다

- **보장 메커니즘** — `processed_event` INSERT 와 실제 처리를 **같은 트랜잭션**에. 중복이면 DuplicateKey 로 조용히 ack.
- **깨지는 조건** — 처리와 기록이 다른 트랜잭션이면 "처리했는데 기록 실패" 가 발생한다. 부수효과가 외부(이메일 등)면 여전히 중복 가능하므로 **외부 호출 자체도 멱등키를 가져야 한다.**
- **검증 방법** — `T-6`: 동일 메시지 10회 주입 → 부수효과 1회, `processed_event` 1행.

### I-20. 일부 인프라 장애 시에도 초과 예매는 발생하지 않는다

- **보장 메커니즘** — **MySQL 하나만 살아있으면 I-8, I-13 은 유지된다.** Redis 와 Kafka 는 정합성 경로에 없다.
- **깨지는 조건** — 좌석 선점 판정을 Redis 캐시나 분산 락에 의존하는 경우.
- **검증 방법** — `T-8`: Testcontainers 로 Redis / MySQL 을 10초 pause. Redis 정지 중에도 이미 입장한 사용자의 좌석 선점이 정상 동작하고 초과 예매 0. `T-9`: 부하 중 앱 kill → 재기동 후 고아 상태 0건.

---

## 정합성 검증 쿼리

부하 테스트 종료 후 **자동 실행**한다. **하나라도 행을 반환하면 CI 실패.**

```sql
-- V-1: 초과 예매 (I-13)
SELECT ts.id, COUNT(*) AS sold, ts.total_seats
  FROM seat_inventory si JOIN train_schedule ts ON ts.id = si.schedule_id
 WHERE si.status = 'SOLD'
 GROUP BY ts.id, ts.total_seats
HAVING COUNT(*) > ts.total_seats;

-- V-2: 좌석당 확정 예약 1건 (I-8)
SELECT seat_inventory_id, COUNT(DISTINCT rs.reservation_id) AS c
  FROM reservation_seat rs JOIN reservation r ON r.id = rs.reservation_id
 WHERE r.status = 'CONFIRMED'
 GROUP BY seat_inventory_id
HAVING c > 1;

-- V-3: 중복 결제 (I-2 / NFR-2)
SELECT reservation_id, COUNT(*) AS c FROM payment
 WHERE status IN ('AUTHORIZED','CAPTURED')
 GROUP BY reservation_id HAVING c > 1;

-- V-4: 금액 정합 (I-16)
SELECT r.id FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.id
 WHERE r.status = 'CONFIRMED'
 GROUP BY r.id, r.total_amount
HAVING SUM(rs.fare) <> r.total_amount;

-- V-5: 고아 상태 (I-14)
SELECT id FROM seat_inventory WHERE status = 'SOLD' AND reservation_id IS NULL;

-- V-6: 돈만 나간 결제 (I-15 / I-17)
SELECT p.id FROM payment p JOIN reservation r ON r.id = p.reservation_id
 WHERE p.status = 'CAPTURED' AND r.status <> 'CONFIRMED';
```

---

## 불변식 주장의 3층 구조 ★

**부하 테스트 통과는 증명이 아니다.** 그 실행에서 위반이 관측되지 않았다는 뜻일 뿐이다.
동시성 버그는 특정 인터리빙에서만 나타나며, 무작위 부하가 그 인터리빙에 도달한다는 보장이 없다.

따라서 주장을 3층으로 분리하고 각각 다른 근거를 댄다.

| 층 | 주장 | 근거 |
|---|---|---|
| **구조적 논증** | "한 행이 `AVAILABLE→HELD` 로 두 번 전이할 수 없다" | InnoDB 행 잠금 + 조건부 UPDATE 의 `affected_rows` 의미론. 이 문서로 논증 |
| **결정적 검증** | "1,000 동시 요청에서 성공은 항상 1" | 동시성 테스트 100회 반복. **순진한 구현으로 먼저 실패시킨 기록**이 이 테스트가 유효함의 증거 |
| **통계적 corroboration** | "부하 30분 / 50,000석에서 위반 0건" | 부하 테스트 + 검증 쿼리 |

README 와 벤치마크 문서에는 **세 층을 모두** 쓴다.
부하 테스트만으로 "증명" 이라고 쓰지 않는다.
