# TASK-002H-B — 자발적 해제와 quota 감소의 같은 트랜잭션 연결

> 관련: [REQUIREMENTS.md](../REQUIREMENTS.md) FR-2.5·P-2, [INVARIANTS.md](../INVARIANTS.md) I-12,
> [TASK-002E](TASK-002E-seat-hold-release.md) — 해제 저장소,
> [TASK-002F](TASK-002F-transaction-boundary.md) — 트랜잭션 경계,
> [TASK-002G-B](TASK-002G-B-quota-lock-order.md) — 잠금 순서,
> [TASK-002G-C](TASK-002G-C-expiry-quota-attribution.md) — 감소량은 실제 변경 수,
> [TASK-002H-A](TASK-002H-A-hold-application-service.md) — 선점 서비스

## 1. 목적과 범위

Task 2H-A 는 선점 경로만 quota 와 연결했다. 이 Task 는 **자발적 해제 경로**를 연결해
좌석 해제와 quota 감소를 하나의 트랜잭션으로 묶는다.

> ### ★ I-12 는 여전히 운영 활성화 전이다
>
> 연결된 것은 **선점·자발적 해제 두 경로**다. **확정(SOLD)과 만료 경로는 여전히 quota 를
> 줄이지 않는다.** 사용자가 결제를 마치거나 홀드가 만료되면 카운터는 그대로 남아
> 그 사용자는 상한을 소진한 채 남는다. REST API 도 없다.
> §8 에 남은 범위를 적었다.

**하지 않은 것** — REST API, 멱등키 저장소, 결제, 확정·만료 quota 연동, 스케줄러·잡 락,
감사 로그·메트릭, 운영 데이터 변경, 자동 backfill, quota 강제 활성화, 새 인덱스.

---

## 2. 해제 계약

| 항목 | 계약 |
|---|---|
| 입력 | `HoldId` + `UserId`. **좌석 목록도 회차도 받지 않는다** |
| 단위 | 홀드. 기존 `releaseAll` 의 계약과 내부 후보 메서드의 package-private 가시성을 유지했다 |
| 회차 | `holdId`·`userId` 에 맞는 **활성 좌석**(`HELD`/`PAYING`)에서 서버가 해석 |
| 회차 조회 | **잠금 없는 조회.** 좌석을 먼저 잠그면 quota → seat 순서가 뒤집힌다 |
| 대상 없음 | **성공 no-op (0 건).** 이미 해제됨·없는 홀드·타 사용자·SOLD 를 구분해 노출하지 않는다 |
| 회차 혼합 | 비정상 데이터. 임의 회차를 고르지 않고 **쓰기 전에** `SaleEventScopeException` |
| 스냅숏 | 조회 결과는 그 시점의 사실이다. 최종 해제 가능 여부는 조건부 UPDATE 가 다시 판단한다 |
| 감소량 | 요청 수도 후보 수도 아닌 **`releaseAll` 의 실제 `affected_rows`** |
| 0 건 | quota 감소를 **호출하지 않는다** |
| 부분 stale | 나머지 해제는 허용한다. 선점의 전부-또는-전무(I-9)와 반대다 |
| 정합성 오류 | 실제로 풀었는데 행이 없거나 감소가 거절되면 `QuotaInconsistencyException` → **좌석 해제까지 롤백** |
| 자동 보정 | **없다.** `ensureRow` 를 부르지 않고 값을 고치지도 않는다. drift 의 증거를 남긴다 |
| 반환값 | `REQUIRED` 참여이므로 **바깥 트랜잭션이 있으면 아직 최종 커밋이 아니다** |

### 왜 0 건의 원인을 구분하지 않는가

구분해 노출하면 홀드 식별자만으로 "이 홀드가 남의 것인가, 이미 팔렸는가" 를 탐색할 수 있다.
규칙 18 의 자연 멱등 계약대로 0 건은 하나의 사실 — "지금 조건에 맞는 활성 좌석이 없다" — 이다.

### 왜 quota 행이 없을 때 만들지 않는가

감소 경로에서 행을 만들면 "좌석은 있는데 카운터가 없었다" 는 drift 의 증거가 사라진다.
활성화 전 backfill(2G-G §4)을 거치지 않은 홀드가 그런 상태이며, 그것은 backfill 로 고쳐야지
해제 경로가 조용히 메워서는 안 된다.

---

## 3. 트랜잭션 경계와 잠금 순서

`ReleaseHoldService.release` 가 `@Transactional(REQUIRED)` 하나로 경계를 소유한다.

```
[트랜잭션]  1. resolveActiveHold(holdId, userId)   잠금 없는 조회 → 회차 (없으면 no-op 반환)
            2. quota.lockRow(회차, userId)         SELECT ... FOR UPDATE  ← quota 먼저
            3. seats.releaseAll(holdId, userId)   후보 재조회 → PK 오름차순 조건부 UPDATE
            4. released == 0 → no-op 반환
               counter 없음 → QuotaInconsistencyException (롤백)
               quota.release(회차, userId, released) → REJECTED → QuotaInconsistencyException (롤백)
```

**잠금 순서는 선점 경로와 같다 — quota → seat.** 1 이 잠금 없는 조회여야 하는 이유가
이것이다. 회차를 알기 위해 좌석을 `FOR UPDATE` 로 읽으면 seat → quota 가 되어
선점 경로(quota → seat)와 교차한다 (TASK-002G-B).

**2 에서 행이 없어도 아직 판단하지 않는다.** 없는 키의 `FOR UPDATE` 는 갭을 잠가 같은 키의
동시 `ensureRow` 와 직렬화되고(2G-G 측정), 실제로 풀린 좌석이 있을 때만 정합성 문제가 된다.
3 이 0 건이면 행이 없어도 no-op 이다 — 줄일 것이 없다.

### 왜 같은 행을 두 번 읽는가

1 의 회차 조회와 3 의 후보 조회는 같은 인덱스로 같은 좌석(≤4행)을 읽는다.
후보 조회를 재사용하려면 `findReleasableSeats` 를 공개해야 하는데, 그러면 호출자가 후보를
골라 홀드 일부만 풀 수 있다 (2E 가 가시성으로 막은 것). 4행 두 번 읽기가 그 계약을 여는 것보다 싸다.

---

## 4. 포트와 배선

| 포트 | 추가한 연산 | 소비자 |
|---|---|---|
| `SaleEventScopePort` | `resolveActiveHold(HoldId, UserId): Optional<SaleEventId>` | 해제 |
| `UserHoldQuotaPort` | `lockRow(…): Optional<Integer>`, `release(…, int): QuotaReleaseOutcome` | 해제 |
| `SeatReleasePort` (신규) | `releaseAll(HoldId, UserId): int` | 해제 |

- `QuotaReleaseOutcome` 을 infra → domain 으로 **옮겼다** (2H-A 의 `QuotaAcquireOutcome` 과 같은 이유, 복제 없음)
- `lockAll` 은 여전히 포트에 없다 — 소비자가 없다
- `ReservationPersistenceConfig` 가 `SeatReleasePort` 빈을 추가했다. 2H-A 와 **같은 DataSource·같은 `DataSourceTransactionManager`** 다
- 기존 저장소의 직접 호출 계약과 테스트는 바꾸지 않았다. `@Override` 와 `implements` 만 붙였다

### 새 조회 SQL 의 실행 계획 — 새 인덱스 없음

`JdbcSaleEventScopeRepository.HOLD_SCOPE_SQL`:

```sql
SELECT ts.sale_event_id
  FROM seat_inventory s FORCE INDEX (idx_seat_inventory_hold)
  STRAIGHT_JOIN train_schedule ts ON ts.id = s.schedule_id
 WHERE s.hold_id = ? AND s.held_by = ? AND s.status IN ('HELD', 'PAYING')
```

EXPLAIN 어서션(`JdbcSaleEventScopeRepositoryTest`, 같은 사용자의 다른 홀드 20개가 있는 상태):

| 테이블 | type | key |
|---|---|---|
| `s` | `ref` | `idx_seat_inventory_hold` (V3) |
| `ts` | `eq_ref` | `PRIMARY` |

`(hold_id)` 인덱스를 고른 근거는 2E 와 같다 — `(held_by, status)` 는 그 사용자의 활성 좌석을
전부 스캔한 뒤 걸러낸다. `STRAIGHT_JOIN` 은 2G-E-B2 와 같은 이유다.
**측정 없이 인덱스를 추가하지 않았다.** 기존 인덱스로 홀드 좌석 수에 갇히는 계획이 나온다.

---

## 5. 검증 결과

### 단위 — `ReleaseHoldServiceTest` (11개, DB 없음)

호출 순서 `resolveActiveHold → lockRow → releaseAll → release(n)`, 해석된 회차로 잠금·감소,
실제 해제 수만큼만 감소, 활성 홀드 없음 → 아무것도 안 함, 실제 0 건 → 감소 미호출,
행 없음+0 건 → 오류 아님, 행 없음+해제됨 → 정합성 오류(`ensureRow` 미호출),
감소 거절 → 정합성 오류, 회차 혼합 → 쓰기 전 거절, 배선 실수 구분, null 거부.

### 통합 — `ReleaseHoldServiceIntegrationTest` (18개, Spring 빈 + MySQL 8.4)

선점은 같은 컨텍스트의 `HoldSeatsService` 로 만들어 quota 와 좌석이 운영 경로 그대로 준비된다.

| 절 | 검증 | 픽스처 주의 |
|---|---|---|
| §1 정상 (3) | 해제 수 = quota 감소, **선점 → 해제 → 재선점**, 여러 운행편 | — |
| §2 no-op (4) | 재해제·없는 홀드·타 사용자·SOLD 모두 0 건, 타인 좌석·quota 불변, 요청자 행 미생성 | SOLD 는 직접 SQL (확정 미연동). 남는 quota 1 은 픽스처가 만든 drift |
| §3 실제 해제 수 (1) | **stale 후보** — 후보 3, 실제 2, quota 3→1 | SOLD 는 직접 SQL |
| §4 정합성 (4) | 행 없음 → 롤백, 부족 → 롤백·값 미보정, 외부 롤백 → 둘 다 복구, 회차 혼합 → 쓰기 전 거절 | 행 없음·부족 상태는 직접 SQL 로 만든 drift |
| §5 동시 (2) | 같은 홀드 동시 해제 3+0·quota 한 번만, 해제·선점 경쟁에서 카운터 = 활성 좌석 ≤ 4 | — |
| §7 RED A 대조군 (3) | **정합성 오류를 삼키면** 행 부재 → 좌석 풀리고 카운터 없음, 부족 → 좌석 풀리고 카운터 2 잔존; 올바른 구현은 HELD 로 되돌림 | 행 없음·부족 상태는 직접 SQL |
| §8 프록시 (1) | 손으로 조립하면 `IllegalStateException` | — |

### infra — `JdbcSaleEventScopeRepositoryTest` §활성 홀드 (5개)

같은 회차·다른 운행편·PAYING 포함, 없는 홀드·타 사용자 → 빈 값, SOLD → 빈 값,
회차 혼합 → 예외, EXPLAIN 어서션.

### stale 후보를 결정적으로 만든 방법 (§3)

바깥 `TransactionTemplate` 에서 먼저 **잠금 없는 읽기**로 `REPEATABLE READ` 스냅숏을 고정하고,
**다른 풀 커넥션(autocommit)** 으로 좌석 하나를 SOLD 로 커밋한 뒤, 서비스를 부른다.
서비스는 바깥에 참여하므로 회차 해석과 후보 조회는 옛 스냅숏(3석 HELD)을 보고,
해제 UPDATE 는 잠금 읽기라 최신(1석 SOLD)을 본다. 후보 3, 실제 2 가 **매번** 나온다 —
스레드 스케줄링에 좌우되지 않으므로 결정적 재현이다. 운영 코드나 동기화 장치를 추가하지 않았다.

### 주장의 층 — §3 은 결정적, §5 는 동시 실행의 보조 검증

**§3 (stale 후보)은 결정적 재현이다.** "스냅숏 고정 → 별도 커넥션 커밋 → 서비스 실행" 순서가
코드로 고정되어 있고 스레드가 하나다. 후보 3 / 실제 2 는 매번 나온다.

**§5 는 그렇지 않다.** `CountDownLatch` 는 두 스레드의 <b>출발</b>만 맞추고 실제 교차 순서를
강제하지 않는다. 기대 결과가 일정하다는 것(합 3+0, 카운터 ≤ 4)은 "이 실행에서 불변식이
깨지지 않았다" 는 관측이지, 모든 교차 순서를 시험했다는 뜻이 아니다.
따라서 §5 는 **동시 실행에서의 보조 검증**으로 분류한다. 결과가 일정하다는 사실만으로
결정적 재현이라 부르지 않는다.

§5 가 지키는 성질의 근거는 테스트가 아니라 **구조적 논증**이다.
- 두 경로(선점·해제)가 모두 **quota → seat 순서**로 잠그므로 같은 사용자·회차의 트랜잭션은
  quota 행에서 직렬화된다 (TASK-002G-B)
- 늦은 해제는 조건부 UPDATE 가 이미 `AVAILABLE` 인 좌석을 걸러 `affected_rows = 0` 이 되고,
  0 이면 감소를 호출하지 않는다. 감소량이 항상 실제 변경 수이므로 이중 감소가 구조적으로 없다
- 해제·선점 경쟁에서 선점이 성공하는지(해제 뒤) 거절되는지(해제 전)는 순서에 따라 갈리며,
  테스트는 두 분기를 모두 받아들이고 **카운터 = 활성 좌석 수 ≤ 4** 만 확인한다

잠금 뒤 배리어는 두지 않았다 — 두면 테스트 자체가 교착한다. 이번 보완에서 동기화 장치나
운영 코드를 추가하지 않았다.

---

## 6. ★ 먼저 실패시킨 기록 (규칙 27)

> **실행 시점을 구분한다.** RED A·B·C 는 구현 직후 운영 코드를 임시로 바꿔 **당시 15개짜리**
> 통합 테스트로 관측한 뒤 원복했고, 원복 후 파일이 바이트 동일함을 확인했다.
> 그 뒤 §7 대조군 3개를 추가했으며 RED A·B·C 를 **재실행하지 않았다.** 아래 실패 수는
> 당시 15개 기준이고 §7 은 포함되지 않는다.

### RED A — 정합성 오류를 삼키고 성공 반환

| 항목 | 값 |
|---|---|
| 당시 실패 | §4 `quota_행이_없으면…`, `quota_가_부족하면…` **2개** |
| 당시 관측 | **예외 미발생을 탐지했다.** 두 테스트는 `assertThatThrownBy` 에서 먼저 실패하므로 그 뒤의 좌석·quota 어서션에는 도달하지 않았다 |
| 커밋 후 상태 | 당시에는 **관측하지 않았다.** 이제 §7 대조군이 실측한다 (아래) |

**§7 대조군의 실측** — 서비스와 같은 순서를 트랜잭션 안에서 실행하되 예외 대신 성공을 돌려주고,
커밋 **후** 좌석과 카운터를 각각 확인한다. 두 경우는 다르다.

| 조건 | 커밋 후 좌석 | 커밋 후 카운터 | 사용자에게 남는 것 |
|---|---|---|---|
| **행 부재** (backfill 안 된 2석 홀드) | 2석 `AVAILABLE` | **여전히 없음** (`-1`) | "상한이 남는다" 가 아니다. 카운터 자체가 없으므로 다음 선점은 0 부터 새로 만든다. 남는 것은 **backfill 이 필요했다는 증거의 소멸**이다 — 활성 좌석이 없어져 V-7b 에도 더 이상 걸리지 않는다 |
| **부족** (활성 4석 / quota 2) | 4석 `AVAILABLE` | **2 로 잔존** (`2-4<0` 거절이 반영되지 않음) | **좌석 0석인데 상한 2 를 소진**한 채 남는다. 다음 선점은 2석까지만 가능하다 |

같은 두 조건에서 올바른 구현은 `QuotaInconsistencyException` 을 던지고 좌석이 `HELD` 로
되돌아온다 (§4, §7 세 번째 테스트). 운영 서비스에는 삼키는 분기가 없다.

### RED B — `released` 대신 잠근 카운터 값 전체로 감소

| 항목 | 값 |
|---|---|
| 당시 실패 | §3 stale, §4 부족 **2개** |
| 당시 관측 | stale: 어서션 실패 — quota 가 1 이 아니라 0. 부족: `assertThatThrownBy` 실패 — 거절돼야 할 감소가 성공 |

부족 케이스가 핵심이다. 활성 4석 / quota 2 에서 `release(counter=2)` 는 `2-2=0` 으로 **성공**해
버린다. 실제 해제 수(4)로 줄여야 `2-4<0` 이 거절된다. 감소량이 왜 "잠근 값" 도 "후보 수" 도
아닌 "실제 변경 수" 여야 하는지를 보여 준다.

### RED C — `@Transactional` 제거

| 항목 | 값 |
|---|---|
| 당시 실패 | **8개** (15개 중) — §1 3개, §2 재해제 1개, §4 행 없음·부족 2개, §5 2개 |
| 실패 원인 | 전부 `lockRow` 의 참여 검사 `IllegalStateException`. 조용히 autocommit 되지 않는다 |

**살아남은 7개의 이유는 두 부류다** (현재 코드와 대조했다).

| 부류 | 테스트 | 이유 |
|---|---|---|
| 서비스 흐름이 `lockRow` 전에 끝나거나 예외를 기대 | §2 없는 홀드·타 사용자·SOLD (3) | `resolveActiveHold` 는 참여 검사 없는 자동커밋 읽기라 트랜잭션 없이도 동작하고, 빈 값을 돌려 `lockRow` 전에 no-op 으로 끝난다 |
| | §4 회차 혼합 (1) | `resolveActiveHold` 가 `lockRow` 전에 `SaleEventScopeException` 을 던지고, 테스트가 그것을 기대한다 |
| | §8 프록시 (1) | 트랜잭션 없는 호출이 거절되는 것을 **기대**하는 테스트다. 서비스에서 지워도 손으로 조립한 쪽은 원래 없었으므로 결과가 같다 |
| 테스트가 외부 `TransactionTemplate` 으로 경계를 열어 서비스가 **참여** | §3 stale (1), §4 외부 롤백 (1) | 서비스에 `@Transactional` 이 없어도 바깥 경계에 저장소들이 참여한다 |

**§2 재해제가 실패한 지점**은 테스트 본문의 두 번째 호출이 아니라 **준비 단계의 첫 해제**다 —
그 호출이 `lockRow` 에서 거절된다.

§7 대조군 3개는 당시 존재하지 않았다. 지금 RED C 를 재실행한다면 §7 은 자체 경계를 열므로
살아남을 것으로 **추정**되지만, 실행하지 않았으므로 단정하지 않는다.

---

## 7. 남은 위험

### ★ 운영 활성화 전에 반드시 필요한 것

| 항목 | 상태 |
|---|---|
| **확정(SOLD) 경로의 quota 감소** | **없다.** 결제를 마치면 카운터가 그대로 남는다 |
| **만료 경로의 quota 감소** | **없다.** 스위퍼도 `@Scheduled` 배선이 없다 (2G-F 다중 실행 계약 유지 예정) |
| **REST API** | 없다. 규칙 18 의 204 매핑 자리가 없다 |
| **멱등키** | 없다. 다만 해제는 자연 멱등이라 재시도가 두 번 줄이지 않는다 (§2) |
| **감사 로그·메트릭** | 없다 |
| **backfill** | 절차만 있다. 거치지 않은 홀드는 해제가 `QuotaInconsistencyException` 으로 거절된다 — **의도된 동작**이지만 활성화 전에 반드시 수행해야 한다 |

### 검증하지 않은 것

- 해제 경로의 **실제 잠금 보유 시간**과 경합 비용
- **확정·만료가 해제와 동시에** 일어나는 경쟁 — 두 경로가 quota 와 미연동이라 지금 검증해도 카운터 결과가 의미 없다
- **데드락 부재** — quota → seat 순서를 지켰고 관측되지 않았을 뿐 증명이 아니다
- `PAYING` 상태 홀드의 해제가 결제 진행 중인 PG 호출과 충돌하는 경우 — 결제 계층이 없다
- 홀드 한 개가 5석 이상인 비정상 데이터 — `release` 가 `IllegalArgumentException` 으로 거절하고 롤백되지만 시험하지 않았다

---

## 8. 재현 명령

```bash
./gradlew :apps:reservation-service:test --tests "*ReleaseHoldServiceTest"
./gradlew :apps:reservation-service:test --tests "*ReleaseHoldServiceIntegrationTest"
./gradlew :modules:reservation-infra:test --tests "*JdbcSaleEventScopeRepositoryTest"
./gradlew check --rerun-tasks
```
