# 결정 — SaleEvent / TrainSchedule 도메인 모델 (Task 2G-E-A)

> TASK-002G-E-A · 2026-08-30
> 관련: [REQUIREMENTS.md](../REQUIREMENTS.md) FR-2.6·P-2, [INVARIANTS.md](../INVARIANTS.md) I-12,
> [CLAUDE.md](../../CLAUDE.md) 규칙 7·27·28
> 선행: [2G-D](TASK-002G-D-quota-scope-contract.md) — quota 범위를 판매 이벤트 단위로 결정

## 1. 문제 정의

[2G-D](TASK-002G-D-quota-scope-contract.md) 는 I-12 의 범위를 `sale_event_id` 단위로 **결정**했지만,
그 식별자가 가리킬 **모델 자체가 없었다.** 2G-D §11 이 남긴 위험 그대로다.

> **`SaleEvent` 모델 부재** — quota 의 FK 대상이 없어 마이그레이션을 쓸 수 없다.

2G-E 전체 범위는 도메인 모델 + `train_schedule` 마이그레이션 + `seat_inventory` 비정규화 판단이다.
**이 Task(2G-E-A)는 그중 순수 도메인 모델만 다룬다.** 마이그레이션·JDBC 저장소·API 는 범위 밖이다.

나눈 이유는 마이그레이션이 되돌리기 어려운 결정이기 때문이다(2G-D §9). 상태 전이 규칙과
소속 계약을 먼저 코드로 고정해두면, 그 다음 스키마 결정이 무엇을 지켜야 하는지가 분명해진다.

---

## 2. RED 기록 (규칙 27)

먼저 테스트를 쓰고 실패를 남겼다. 브랜치 `feat/TASK-002G-E-A-sale-event-domain` 의 시작 상태다.

```
./gradlew :modules:reservation-domain:compileTestJava
> Task :modules:reservation-domain:compileTestJava FAILED
  error: cannot find symbol  symbol: class SaleEventId
  error: cannot find symbol  symbol: class SaleEventStatus
  error: cannot find symbol  symbol: class TrainScheduleId
  ...
  35 errors
```

RED 는 `SaleEventIdTest` · `SaleEventTest` · `TrainScheduleIdTest` 세 파일이 만들었다.
**이 Task 의 RED 는 컴파일 실패다.** 동시성 결함의 재현이 아니다 —
순수 도메인 모델에는 재현할 인터리빙이 없다. 여기서 고정하는 것은 전이 규칙이고,
경합 방어의 재현은 운영 저장소를 만드는 후속 Task 의 몫이다.

---

## 3. 결정 1 — 상태 전이표

2G-D §5 의 스키마 초안이 `SCHEDULED | OPEN | CLOSED` 세 값을 적었다. 전이를 다음과 같이 정했다.

```
            open                      close
SCHEDULED   → OPEN (now >= opensAt)   ✗ 전이 위반
OPEN        ✗ 전이 위반                → CLOSED
CLOSED      ✗ 전이 위반                ✗ 전이 위반   ★ 터미널
```

**허용 2개, 거부 4개.** 3개 상태 × 2개 행위 = **6개 조합을 전부** 표에 넣었다.
`SeatTransitionTableTest` 와 같은 방식이다.

커버리지 검증은 **개수 합계만 맞추지 않는다.** 같은 조합을 두 번 적고 다른 조합을 빠뜨려도
합계는 맞기 때문이다. 그래서 `(상태, 행위)` 조합 자체를 만들어 **중복 없이 전부** 있는지
확인한다. 새 상태나 행위를 추가하면 이 검사가 먼저 깨진다 (규칙 28).

### `SCHEDULED → CLOSED` 를 거부한 이유

**열린 적 없는 회차를 닫는다는 것이 업무적으로 무엇인지 정해지지 않았다.**
"오픈 전 취소" 로 읽으면 `CLOSED` 가 *정상 마감*과 *취소*를 동시에 뜻하게 되고, 그러면
"이 회차는 팔리다 끝난 것인가 열리지도 못한 것인가" 를 상태만 보고 구분할 수 없다.

취소가 필요하다는 이유로 `CANCELLED` 상태나 `cancel()` 을 추가하지도 않았다.
그것은 별도의 업무 요구사항과 상태 모델 결정을 거쳐야 한다 (§7 미결정 항목).

### `CLOSED` 를 터미널로 둔 이유

이 계약에 `CLOSED` 에서 나가는 전이는 없다. 재오픈 정책은 요구사항에 없으며 정하지 않았다.
`Seat` 의 `SOLD` 가 터미널인 것과 같은 성격이다.

### `open` 은 시각을 받고 `close` 는 받지 않는다

| | 시각 파라미터 | 근거 |
|---|---|---|
| `open(Instant now)` | **받는다** | 회차를 "하나의 오픈 시각으로 운영하는 단위" 로 정의한 2G-D §4 계약. 경계는 `now >= opensAt` 으로 DB 의 `opens_at <= NOW(3)` 과 같게 맞췄다 |
| `close()` | **받지 않는다** | `closesAt` 은 **예정** 시각일 뿐이다. 매진·운영 중단으로 인한 조기 마감은 정상 운영 행위이므로, `now >= closesAt` 을 요구하면 그 경로가 막힌다 |

**따라서 `closesAt` 은 이 도메인 안에서 판매 기간의 정합성 검증에만 쓰인다.** 마감 판정의 근거가
아니다. 예정 시각에 자동으로 닫는 것은 배치의 책임이며 이 Task 범위가 아니다.

도메인은 시각을 스스로 읽지 않는다. `Instant.now()` 를 도메인 안에서 부르면 인스턴스 간
클럭 드리프트가 판정에 섞인다 (규칙 7). `Seat.expire(Instant now)` 와 같은 원칙이다.

### 이 모델이 보장하지 않는 것

**동시성은 막지 못한다.** 두 스레드가 같은 인스턴스에 `open()` 을 호출하면 둘 다 성공할 수 있다.
운영 방어선은 조건부 UPDATE 이며, 이 Task 는 그것을 만들지 않았다.

```sql
-- 후속 Task 가 만들 형태 (아직 없다)
UPDATE sale_event SET status='OPEN'
 WHERE id=? AND status='SCHEDULED' AND opens_at <= NOW(3);
```

---

## 4. 결정 2 — 소속 변경 방지의 **층위를 나눈다**

2G-D §4 의 계약이다.

> 판매가 시작되거나 좌석 재고가 만들어진 뒤 `TrainSchedule` 의 `SaleEvent` 소속을 바꾸지 않는다.
> 바꾸면 **이미 쌓인 quota 의 의미가 달라진다.**

**이 Task 는 그 계약의 일부만 보장한다.** 경계를 먼저 적는다.

| | 보장 | 수단 |
|---|---|---|
| ✅ | 도메인 객체 **인스턴스**는 불변이다. 생성 후 그 인스턴스의 `saleEventId` 는 바뀌지 않는다 | 모든 필드가 `final` |
| ✅ | **공개 재배정 API 를 제공하지 않는다** | 소속을 받는 인스턴스 메서드는 질의 `belongsTo` 하나뿐 |
| ❌ | 같은 `TrainScheduleId` 의 **영속 소속**이 다른 `SaleEventId` 로 갱신되는 것 | **막지 못한다.** Task 2G-E-B 의 repository/DB 계약 몫 |

**세 번째 줄이 중요하다.** 같은 `TrainScheduleId` 에 다른 소속을 담은 스냅샷을 넘기면 도메인은
그대로 복원한다. 도메인은 이전에 저장된 값을 알지 못하므로 판정할 근거가 없다.
즉 **aggregate identity 수준의 소속 불변은 아직 어디에서도 강제되지 않는다.**
`UPDATE train_schedule SET sale_event_id = ...` 를 막을 수단이 필요하다.

### 왜 상태 규칙을 쓰지 않았나

2G-D §12 는 이것을 "판매 시작 후 소속 변경 금지를 **상태 규칙**으로 강제" 로 적었다.
그 방식은 택하지 않았다.

1. **판매 시작 전에도 안전하지 않다.** 좌석 재고와 quota 행은 회차가 열리기 전에도 만들어질 수
   있다. "아직 `SCHEDULED` 니까 옮겨도 된다" 는 판단은 그 경우 틀린다.
   즉 `OPEN` 여부는 **재배정 안전성의 올바른 기준이 아니다.**
2. **규칙을 지키려면 다른 애그리거트의 상태를 읽어야 한다.** 운행편이 판매 회차의 상태에 따라
   자기 행동을 바꾸면 두 애그리거트가 한 트랜잭션에 묶인다.

상태 규칙이 답이 아니라고 해서 도메인이 계약 전체를 지킬 수 있게 되는 것은 아니다.
그래서 위 표의 두 줄만 맡고 나머지는 2G-E-B 로 넘긴다.

### 리플렉션 검사가 증명하는 것

테스트가 세 가지를 고정한다 — 모든 인스턴스 필드 `final`, `void` 반환 public 인스턴스 메서드
0개, `SaleEventId` 를 받는 인스턴스 메서드는 `belongsTo` 하나뿐.

**이 검사들은 aggregate identity 수준의 소속 불변을 증명하지 않는다.**
증명하는 것은 "이 객체를 통해 소속을 바꿀 수 없다" 는 것뿐이다.
목적은 "지금 그렇다" 가 아니라 **"앞으로도 그래야 한다"** 를 고정하는 것이다 —
나중에 누군가 `reassign(SaleEventId)` 를 추가하면 세 번째 검사가 깨진다.

---

## 5. 결정 3 — 스냅샷과 복원

`SaleEventSnapshot` · `TrainScheduleSnapshot` 을 두고 `restore(snapshot)` 로 복원한다.
`Seat.restore(SeatSnapshot)` 과 같은 구조다.

### 복원이 전이를 재실행하지 않아야 하는 이유

`CLOSED` 회차를 만들려고 `schedule → open → close` 를 다시 돌리면, `open()` 의 오픈 시각 검사가
**과거에 유효했던 데이터를 현재 규칙으로 다시 판정**하게 되어 읽기가 실패한다.
오픈 시각이 미래로 보이는 행이 이미 `OPEN` 으로 저장돼 있다면 그대로 읽어야 한다.
테스트가 이 경우를 고정한다.

### `SeatSnapshot` 과 다른 점

`SeatSnapshot` 은 **상태마다 허용되는 필드 조합이 다르다** (`SOLD` 에는 `holdId` 가 없어야 한다).
**판매 회차에는 그런 상태 의존 조합이 없다.** 세 상태 모두 같은 필드를 가지며, 상태는 판매
기간과 독립적으로 운영이 정한다 — `close()` 가 시각을 요구하지 않는 것과 같은 이유다(§3).

없는 규칙을 만들어 표를 흉내 내지 않았다. 스냅샷 타입을 둔 이유는 위의 **전이 재실행 차단**과
**값 검증 집중** 두 가지다.

### 예외 타입을 나눈 이유 — 그리고 HTTP 매핑을 정하지 않은 이유

같은 규칙 위반이라도 의미가 다르다.

| 경로 | 예외 | 의미 |
|---|---|---|
| `SaleEvent.schedule(...)` | `IllegalArgumentException` / `NullPointerException` | 잘못된 입력 |
| `SaleEventSnapshot` | `SaleEventRestoreException` | **저장소에 손상된 행이 있다.** 운영에서는 서버 측 정합성 오류로 다뤄야 한다 |
| `TrainScheduleSnapshot` | `TrainScheduleRestoreException` | 위와 같음 |
| 전이·시간 조건 위반 | `SaleEventStateTransitionException` | 현재 상태 또는 시간 조건에서 수행할 수 없는 행위 |

`SeatRestoreException` 을 `IllegalArgumentException` 과 구분한 것과 같은 판단이다.

**`SaleEventStateTransitionException` 에 HTTP 상태나 알람 정책을 붙이지 않았다.**
같은 예외가 다음을 모두 담기 때문이다.

- 오픈 예정 시각 이전의 `open(now)` — 정상적인 거부일 수 있다
- 이미 `OPEN` 인 회차를 다시 여는 요청 — 재시도나 멱등 요청일 수 있다
- 이미 `CLOSED` 인 회차를 다시 닫는 요청 — 위와 같다
- 전이표에 없는 관리 명령 — 버그일 수 있다

정상 거부와 버그가 한 타입에 섞여 있으므로 "대개 버그" 라고 단정할 수 없고,
매핑은 **요청 맥락과 멱등성 정책을 아는 계층**이 결정해야 한다.
애플리케이션 서비스와 REST API 는 아직 없다 (Task 2H).

이번 보완에서 예외 타입을 더 잘게 쪼개지도 않았다. 쪼개는 기준 자체가 앱 계층의 정책이다.

규칙 자체는 `SaleEventRules`(package-private 술어)가 한 번만 갖고 있고, **예외는 호출부가 정한다.**
테스트가 두 경로에 같은 기간 규칙이 적용되는지 확인한다 — 한쪽만 느슨해지면 깨진다.

`TrainScheduleRestoreException` 이 잡는 가장 중요한 손상은 **소속 없는 운행편**이다.
그런 행이 있으면 그 운행편의 좌석은 어느 quota 카운터에도 귀속되지 않아 I-12 의 상한 밖에 놓인다.

---

## 6. 검증 결과

`./gradlew :modules:reservation-domain:test` — **74 tests, 모두 통과** (모듈 전체 202 tests).
인프라 불필요(순수 Java).

### `saleevent` (56)

| 클래스 | 수 | 검증 |
|---|---|---|
| `SaleEventIdTest` | 5 | 양수 검증, 동등성, **잠금 순서를 위한 `Comparable`** |
| `SaleEventTest` | 10 | 생성 불변식 9 + 도메인이 시각을 스스로 읽지 않음 1 |
| `SaleEventTransitionTableTest` | 23 | 전이표 6조합 전수(허용 2 / 거부 4), **거부된 전이의 상태 불변**, 커버리지 3, 오픈 시각 경계 4, 마감 4 |
| `SaleEventRestoreTest` | 18 | 세 상태 복원, **복원이 오픈 시각을 재판정하지 않음**, 손상된 행 7종, 예외 타입 구분, 규칙 공유, 왕복 |

### `schedule` (18)

| 클래스 | 수 | 검증 |
|---|---|---|
| `TrainScheduleIdTest` | 4 | 양수 검증, 동등성 |
| `TrainScheduleTest` | 14 | 편성 5(같은 회차의 여러 운행편이 하나의 quota 범위 / 식별자 암묵 대입 금지 포함), **인스턴스 불변·재배정 API 부재 3**, 복원 6 |

### 전이표 커버리지 — 허용 2 / 거부 4

| | open | close |
|---|---|---|
| **SCHEDULED** | 통과 (→ OPEN) | 통과 (거부, 상태 불변) |
| **OPEN** | 통과 (거부, 상태 불변) | 통과 (→ CLOSED) |
| **CLOSED** | 통과 (거부, 상태 불변) | 통과 (거부, 상태 불변) |

커버리지 검증은 개수 합계에 더해 **`(상태, 행위)` 조합의 중복 없음과 누락 없음**을 함께 확인한다.

> 이 통과는 **도메인 규칙이 고정됐다**는 뜻이지, 운영에서 I-12 가 지켜진다는 뜻이 아니다 (규칙 30).

---

## 7. 검증하지 않은 것 · 미결정으로 남긴 것

### 검증하지 않은 것

- **운영 저장·조회** — `sale_event` · `train_schedule` 테이블도 마이그레이션도 저장소도 없다
- **동시 전이** — 두 요청이 같은 회차를 동시에 여는 경우. 도메인 객체는 이것을 막지 않는다(§3)
- **같은 `TrainScheduleId` 의 영속 소속 변경** — 도메인은 인스턴스 불변과 재배정 API 부재만
  보장한다(§4). **테스트 범위도 아니다**
- ~~**`seat_inventory` 비정규화 여부**~~ → [2G-E-B1](TASK-002G-E-B1-schema-decision.md) §8 에서 **정규화 채택**
- ~~**`scheduleId → saleEventId` 해석 경로의 비용**~~ → [2G-E-B1](TASK-002G-E-B1-schema-decision.md) §7 에서 실측
- **quota 카운터와의 실제 연결** — `user_hold_quota` 는 여전히 테스트 전용이다
- 2G-C·2G-D 가 남긴 미검증 항목은 **그대로 남아 있다**

### 미결정으로 남긴 것

| 항목 | 내용 |
|---|---|
| **오픈 전 취소 정책** | 열린 적 없는 회차를 취소한다는 것의 업무적 의미, 그것이 `CLOSED` 인지 별도 상태인지, 그때 quota 를 어떻게 다루는지 — **아무것도 정해지지 않았다.** 별도 업무 요구사항과 상태 모델 결정이 필요하다 (§3) |
| **전이 예외의 HTTP 매핑과 알람** | 정상 거부와 버그가 한 예외에 섞여 있다. 요청 맥락과 멱등성 정책을 아는 계층이 정한다 (§5, Task 2H) |
| **재오픈 정책** | 요구사항에 없다. `CLOSED` 는 터미널로 두었다 (§3) |
| **`closesAt` 자동 마감의 주체** | 배치가 필요하며 정해지지 않았다 |

---

## 8. 남은 위험

| 항목 | 내용 |
|---|---|
| **I-12 는 여전히 운영 미구현** | 이 Task 는 quota 범위가 가리킬 모델을 만들었을 뿐이다. 테이블·저장소·서비스가 없다 |
| **소속 변경의 최종 방어선 부재** | 도메인은 **인스턴스 불변과 공개 재배정 API 부재**만 보장한다. 같은 `TrainScheduleId` 의 영속 소속이 갱신되는 것(`UPDATE train_schedule SET sale_event_id=...`)은 **아직 어디에서도 막히지 않는다** *(→ [2G-E-B1](TASK-002G-E-B1-schema-decision.md) §9~§11 이 수단을 비교하고 골랐다. FK 만으로는 막히지 않는다는 것이 실측됐고, 적용은 2G-E-B2 다)* |
| **`close()` 의 시각 비검증** | 조기 마감을 허용한 대가로, 잘못된 마감을 도메인이 걸러내지 못한다. 권한 검사는 앱 계층 몫이다 |
| **오픈 전 회차를 끝낼 방법 없음** | `SCHEDULED → CLOSED` 를 거부했으므로, 잘못 등록된 회차는 오픈 시각이 될 때까지 `SCHEDULED` 로 남는다. 취소 정책이 정해지기 전까지의 알려진 공백이다 (§7) |
| **자동 마감 주체 미정** | `closesAt` 이 지나도 아무도 닫지 않는다. 배치가 필요하며 정해지지 않았다 |
| **`SaleEventStatus` 와 선점 경로의 연결 없음** | `isOpen()` 을 실제 선점 요청이 검사하도록 배선하는 것은 후속 Task 다 |
| ~~비정규화 여부 미결정~~ | *(→ [2G-E-B1](TASK-002G-E-B1-schema-decision.md) §8 정규화 채택. 조인 비용은 요청당 최대 6행으로 실측됐고 전체 크기와 무관하다)* |
| **대기열 범위와의 정합** | 2G-D §7 그대로 미결정 |

---

## 9. 후속 Task 범위

1. ~~**Task 2G-E-B — 운영 스키마와 저장소**~~ → 둘로 나뉘었다.
   - **2G-E-B1 (완료)** — [2G-E-B1](TASK-002G-E-B1-schema-decision.md) : 정규화 채택, 소속 방어 수단 비교와 선택,
     migration 순서 결정. **실험과 결정만이며 마이그레이션을 적용하지 않았다**
   - **2G-E-B2 (남음)** — V4·V5·V6 적용, 운영 저장소, 회차 전이의 조건부 UPDATE,
     테스트 fixture 이행, 트리거 적용 여부 판단
2. **Task 2G-F — 만료 배치 운영 계약** (2G-C §10 의 8가지)
3. **Task 2G-G — `user_hold_quota` 마이그레이션과 운영 저장소**
4. **Task 2H — 애플리케이션 서비스와 트랜잭션 경계 소유**

---

## 10. 재현 명령

```bash
./gradlew :modules:reservation-domain:test \
  --tests "com.railgate.reservation.saleevent.*" \
  --tests "com.railgate.reservation.schedule.*"
```

Docker 가 필요 없다. 순수 Java 도메인 테스트다.
