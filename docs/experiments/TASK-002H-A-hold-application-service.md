# TASK-002H-A — 좌석 선점 애플리케이션 서비스

> 관련: [REQUIREMENTS.md](../REQUIREMENTS.md) FR-2.3·P-2·P-4,
> [INVARIANTS.md](../INVARIANTS.md) I-9·I-12,
> [TASK-002G-G](TASK-002G-G-user-hold-quota.md),
> [TASK-002F](TASK-002F-transaction-boundary.md) — 트랜잭션 경계,
> [TASK-002G-F](TASK-002G-F-expiry-sweeper-operating-contract.md) — 만료 스위퍼 운영 계약

## 1. 목적과 범위

Task 2G-G 는 I-12 의 **저장 계층**을 만들었지만 그것을 부르는 주체가 없었다.
이 Task 는 **선점 유스케이스 하나**를 배선해 quota 확보와 좌석 선점을
하나의 트랜잭션으로 묶는다.

> ### ★ I-12 는 여전히 요청 경로 전체에 강제되지 않는다
>
> **연결된 것은 선점 경로 하나뿐이다.** 확정·자발적 해제·만료 세 이탈 경로 중
> 어느 것도 quota 를 줄이지 않으므로, **카운터는 실제 보유 좌석보다 계속 커진다.**
> 사용자가 4석을 잡았다 놓아도 카운터는 4에 머물러 다시는 선점하지 못한다.
>
> REST API 도 없어 실제 HTTP 요청은 여전히 이 코드를 통과하지 않는다.
> 이 Task 를 "1인당 4석 제한 완료" 로 읽으면 안 된다. §8 에 남은 범위를 적었다.

**하지 않은 것** — REST API, 멱등키 저장소, 결제, quota 감소, 스위퍼·스케줄러,
프론트엔드, 자동 backfill, 운영 데이터 변경, quota 강제 활성화.

---

## 2. 트랜잭션 경계

**`HoldSeatsService.hold` 가 경계를 소유한다.** `@Transactional`(기본 `REQUIRED`) 하나뿐이다.

```
[트랜잭션 밖]  HoldSeatsCommand 생성 — 빈 목록·중복·상한 초과를 여기서 거부
       │
[트랜잭션 열림] ─────────────────────────────────────────
       ├─ 1. SaleEventScopePort.resolve(seatIds)   → 회차 해석
       ├─ 2. UserHoldQuotaPort.ensureRow           → 카운터 행 확보
       ├─ 3. UserHoldQuotaPort.tryAcquire(n)       → 조건부 증가 (규칙 8)
       └─ 4. SeatHoldPort.holdAll                  → 다좌석 원자 선점 (I-9)
[트랜잭션 커밋] ─────────────────────────────────────────
```

### 왜 저장소가 아니라 서비스가 여는가

저장소들은 Task 2F·2G-G 이후 **트랜잭션을 열지 않는다.** 열려 있는 트랜잭션에 참여할 뿐이고,
참여하지 않았으면 SQL 을 보내기 전에 거부한다. 따라서 누군가는 경계를 열어야 하며 그 자리가
애플리케이션 서비스다. 저장소가 각자 열면 quota 와 좌석이 서로 다른 시점에 확정된다.

### 왜 순서가 quota → seat 인가

TASK-002G-B 가 정한 잠금 순서다. 뒤집으면 좌석을 잡은 트랜잭션이 quota 를 기다리고
quota 를 잡은 트랜잭션이 좌석을 기다리는 순환이 생긴다.
좌석들 **사이**의 순서는 저장소가 PK 오름차순으로 유지한다 (규칙 2).

### 검증 순서가 DB 접근보다 앞인 이유

`HoldSeatsCommand` 생성자가 검증하므로 잘못된 요청은 **트랜잭션이 열리기 전에** 거부된다.
서비스 안에서 검증하면 잘못된 입력 때문에 커넥션을 점유한다.

### 클라이언트가 회차를 보내지 않는다

`HoldSeatsCommand` 에 `saleEventId` 필드가 **없다.** 값을 받아 그대로 쓰면 사용자가 그것을
바꿔 자기 quota 를 무관한 회차에 쌓을 수 있고, 실제로 좌석을 잡는 회차의 카운터는 늘지 않아
**I-12 가 우회된다.** 회차는 좌석으로부터 서버가 해석한다.

같은 회차의 **여러 운행편**은 하나의 회차로 해석되므로 quota 가 자연히 합산된다.
범위가 운행편이었다면 사용자가 운행편을 나눠 상한을 우회했을 것이다.

---

## 3. 실패 계약

| 상황 | 표현 | 좌석을 건드리는가 | quota 가 남는가 |
|---|---|---|---|
| 빈 목록 / 중복 / 5석 이상 | `IllegalArgumentException` (명령 생성) | 아니오 | 아니오 |
| 없는 좌석 섞임 | `SaleEventScopeException` | 아니오 | 아니오 |
| 서로 다른 회차 혼합 | `SaleEventScopeException` | 아니오 | 아니오 |
| **1인당 상한 초과** | **`QuotaExceededException`** | **아니오** | 아니오 (증가 자체가 없다) |
| **좌석 경합** | **`SeatUnavailableException`** | 되돌아감 (savepoint) | **아니오 (롤백)** |
| 트랜잭션 배선 실수 | `IllegalStateException` | 아니오 | 아니오 |

### 상한 초과와 좌석 경합이 다른 타입인 이유

원인도 대응도 다르다.

- **경합** — 다른 사람이 먼저 가져갔다. **다른 좌석을 고르면 성공할 수 있다.**
- **상한 초과** — 이미 가진 좌석 때문이다. **다른 좌석을 골라도 실패한다.**
  기존 홀드를 풀거나 만료돼야 한다.

하나의 타입으로 합치면 호출부가 둘을 구분할 수 없고 사용자는 재시도해도
계속 실패하는 이유를 알 수 없다.

### 포트는 enum, 서비스는 예외 — 억지로 통일하지 않는다

두 계층이 답하는 질문이 다르다.

| 계층 | 질문 | 표현 |
|---|---|---|
| `UserHoldQuotaPort` | "이 확보 시도가 성공했는가?" | `QuotaAcquireOutcome` **enum** |
| `HoldSeatsService` | "이 선점 유스케이스가 성공했는가?" | **예외** (`QuotaExceededException`) |

포트에게 확보 실패는 **정상적인 두 결과 중 하나**다. 예외로 만들면 한도 도달이라는
흔한 결과에 스택 트레이스 비용이 들고, 호출부가 `try/catch` 로 정상 흐름을 쓰게 된다.

서비스에게는 같은 사실이 **유스케이스 전체의 실패**다. 뒤이을 좌석 선점을 하면 안 되고,
호출부에 성공 결과를 돌려줄 수도 없다. 값으로 돌려주면 호출부가 확인을 잊는 순간
좌석 없는 성공이 된다.

**두 표현을 하나로 맞추면 한쪽이 나빠진다.** 포트를 예외로 바꾸면 정상 흐름이
예외 제어가 되고, 서비스를 값으로 바꾸면 실패를 무시할 수 있게 된다. 변환 지점을
서비스 한 곳에 두고 그대로 둔다.

`QuotaAcquireOutcome.LIMIT_EXCEEDED` 는 **상한 초과와 카운터 행 부재를 함께** 뜻한다.
서비스가 같은 트랜잭션에서 `ensureRow` 를 먼저 부르므로 행이 있음이 보장되고,
그래서 **그 결과를 상한 초과로 해석할 전제**가 성립한다. 이 전제 없이 그 결과를
상한 초과로 읽으면 안 된다.

### HTTP 상태 코드는 이번에 결정하지 않았다

규칙 21(409 = 좌석 경합)과 규칙 22(429 = 입장 제한, `Retry-After` 필수)를 함께 보고
상한 초과가 **어느 쪽도 아닌 제3의 상태**인지까지 검토해야 한다.
REST API 가 없으므로 미룬다. `ControllerAdvice` 도 만들지 않았다.

**다만 분류만은 미리 못 박아 둔다. 상한 초과는 정상적인 업무 거절이다.**
좌석 경합(규칙 21)과 마찬가지로 **오류율과 ERROR 로그에 넣지 않는다.**
향후 API 가 이것을 500 이나 ERROR 로 처리하면, 명절 예매처럼 한도 도달이 흔한 상황에서
**지표가 거짓이 되고 진짜 장애가 묻힌다.**

### ★ 예외를 트랜잭션 안에서 삼키지 않는다

서비스는 `SeatUnavailableException` 을 잡지 않는다. 잡으면 좌석의 부분 갱신은
저장소의 savepoint 로 되돌아가는 반면 **같은 트랜잭션의 quota 증가는 그대로 커밋된다.**
좌석 없이 카운터만 오르는 상태이며 drift 검사(V-7a)가 뒤늦게 잡아낼 뿐이다.

이것은 추론이 아니라 **재현했다** — §7 의 RED 1 을 보라.

---

## 4. 모듈 경계와 포트

```
apps/reservation-service/application/hold/HoldSeatsService
        │ 의존
        ▼
modules/reservation-domain (순수 Java 포트)
        SaleEventScopePort   ─┐
        UserHoldQuotaPort    ─┼─ 구현
        SeatHoldPort         ─┘
                              ▼
modules/reservation-infra
        JdbcSaleEventScopeRepository
        JdbcUserHoldQuotaRepository
        JdbcMultiSeatHoldRepository
                              ▲
apps/reservation-service/config/ReservationPersistenceConfig ─ 연결
```

**서비스는 구현체를 모른다.** `reservation-infra` 를 아는 것은 구성 클래스뿐이다.
빈 타입도 포트로 노출한다 — 구현체 타입으로 노출하면 모듈 경계가 구성 파일 밖으로 샌다.

### 반환 타입도 infra 에 묶이지 않게 했다

포트가 순수 Java 이려면 반환 타입과 예외도 그래야 한다. 두 타입을 도메인으로 옮겼다.

| 타입 | 이전 | 이후 |
|---|---|---|
| `QuotaAcquireOutcome` | `infra.quota` | `com.railgate.reservation.quota` |
| `SaleEventScopeException` | `infra.saleevent` | `com.railgate.reservation.saleevent` |

**복제하지 않고 옮겼다.** 의미가 같은 enum 이 두 벌 있으면 반드시 어긋난다.
`SeatUnavailableException` 은 이미 도메인에 있었다.

### 쓰지 않는 포트를 만들지 않았다

`UserHoldQuotaPort` 에 `release`·`lockAll` 이 **없다.** 선점 경로가 쓰지 않기 때문이다.
확정·해제·만료 경로가 생길 때 그 경로가 필요로 하는 연산을 추가한다.
미리 노출하면 "누가 무엇을 호출하는가" 가 흐려진다.

빈 서비스를 거치는 중간 계층도 만들지 않았다.

---

## 5. 배선과 DataSource 구성

`ReservationPersistenceConfig` 가 **하나의 `DataSource` 와 하나의 트랜잭션 관리자**를
세 저장소에 준다. 다른 DataSource 를 주면 두 변경이 서로 다른 커넥션에서 일어나 한쪽만
커밋된다. 저장소들이 각자 참여 검사로 거부하지만 그것은 **런타임 마지막 방어선**이므로,
배선 자체를 한 곳에 모아 실수할 자리를 없앤다.

### `innodb_lock_wait_timeout = 3` 의 책임 위치 (규칙 9)

세션 변수이며, **이 프로젝트는 그 설정 책임을 Hikari `connection-init-sql` 에 두기로 했다.**
`application.yml` 의 `spring.datasource.hikari.connection-init-sql` 이 커넥션 생성 시
한 번 적용한다. 다른 방법이 없어서가 아니라 고른 것이다 — 트랜잭션마다 `SET` 을 보내면
요청당 왕복이 하나 늘고, 저장소가 각자 보내면 저장소마다 같은 코드가 생긴다.
`MySqlTestSupport` 픽스처와 같은 방식이고, **실제로 적용됐는지 테스트가 확인한다**
(`SELECT @@SESSION.innodb_lock_wait_timeout` = 3).

### Spring Boot 4 에서 관측한 것

`spring-boot-autoconfigure` 에 **Flyway 자동 구성이 더 이상 들어 있지 않다.**
Boot 4 가 자동 구성을 모듈별로 쪼갰기 때문이다. 없으면 컨텍스트는 정상적으로 뜨지만
마이그레이션이 **조용히 실행되지 않는다** — `Table 'railgate.seat_inventory' doesn't exist`
로 관측했다. `org.springframework.boot:spring-boot-flyway` 를 명시했다.

---

## 6. 검증 결과

### 단위 테스트 — `HoldSeatsServiceTest` (15개)

DB 없이 **호출 순서와 실패 계약**을 고정한다. Mockito 대신 손으로 만든 기록 스텁을 쓴다 —
검증 대상이 호출 순서라 기록을 직접 읽는 편이 의도가 분명하다.

- 입력 검증 6개 — null·빈 목록·중복·상한 초과, **검증 실패 시 어떤 포트도 호출되지 않음**
- 호출 순서 4개 — `resolve → ensureRow → tryAcquire(n) → holdAll`,
  해석된 회차를 quota 범위로 사용, 요청 좌석 수만큼 **한 번에** 확보
- 실패 계약 5개 — 상한 초과 시 좌석 미접근, 경합 전파, 회차 실패 전파, 배선 실수 구분

### 통합 테스트 — `HoldSeatsServiceIntegrationTest` (15개, 실제 MySQL 8.4)

**Spring 이 배선한 빈**을 호출한다. 손으로 조립한 객체에는 `@Transactional` 프록시가 없어
트랜잭션에 대해 아무것도 증명하지 못한다.

| 절 | 검증 |
|---|---|
| §1 정상 선점 (3) | quota·좌석 함께 반영, 회차를 좌석에서 해석, **같은 회차의 여러 운행편 합산** |
| §2 요청 거부 (3) | 없는 좌석 혼합, 서로 다른 회차 혼합, 명령 생성 단계 거부 |
| §3 상한 초과 (2) | 누적 5석째 거부·좌석 미접근, **사용자·회차 간 독립성** |
| §4 경합 복구 (2) | **부분 경합 시 quota 와 좌석 모두 복구**, 실패 뒤 상한 미소진 |
| §5 동시 요청 (1) | **같은 사용자 동시 3석 요청 두 개 중 하나만 성공, 최종 3석** |
| §6 외부 롤백 (1) | 서비스 성공 후 외부 롤백 시 quota·좌석 모두 복구 |
| §7 프록시 (1) | 손으로 조립한 서비스는 `IllegalStateException` 으로 거부 |
| §8 RED 1 대조군 (2) | **예외를 삼키면 좌석 없이 quota 만 커밋됨을 관측**, 같은 조건에서 올바른 구현은 남기지 않음 |

동시성 테스트는 `CountDownLatch` 배리어 + timeout + `Future.get()` 결과 회수를 쓴다
(`Thread.sleep` 없음, 규칙 25). 통합 테스트는 Testcontainers MySQL 8.4 다 (규칙 26).

### 컨텍스트 테스트 — `ReservationServiceApplicationTest` (4개)

컨텍스트 기동, 포트가 infra 구현체로 배선됨, **세션 `innodb_lock_wait_timeout` = 3**,
Flyway 가 V6 까지 적용.

---

## 7. ★ 먼저 실패시킨 기록 (규칙 27)

새 방어를 넣을 때 순진한 구현으로 먼저 실패시켰다. **한 번도 실패한 적 없는 테스트는
테스트가 아니다.**

> **주장의 층을 구분한다** (규칙 30). "실제로 실행했다" 와 "결정적이다" 는 다른 말이다.
> - **구조적 논증** — 코드를 읽어 도출한 것. 재현 기록이 아니다.
> - **결정적 재현** — 순진한 구현을 넣고 실행했고, **실행 순서가 고정되어 매번 같은
>   결과가 나오는** 것. 단일 스레드이거나 배리어가 순서를 강제하는 경우다.
> - **동시 실행에서 관측한 결과** — 순진한 구현을 넣고 실제로 실행했지만
>   **스레드 스케줄링에 따라 결과가 달라질 수 있는** 것. 관측한 한 번의 결과는
>   사실이지만 매번 같다고 단정하지 않는다.
>
> 아래 표의 "관측한 것" 열은 **실제로 실행한 결과만** 적고, 각 RED 의 제목에
> 어느 층인지를 표시한다.

### RED 1 — 경합 예외를 삼키면 좌석 없이 quota 만 남는다 (결정적 재현)

| 항목 | 값 |
|---|---|
| 재현 위치 | `HoldSeatsServiceIntegrationTest` **§8** (상시 테스트로 남아 있다) |
| 순진한 구현 | 서비스와 **같은 순서**를 실행하되 `SeatUnavailableException` 만 `catch` |
| 초기 데이터 | 좌석 3개(`1A`·`1B`·`1C`), 그중 `1B` 를 `USER_B` 가 선점 |
| 관측한 것 | 예외 없이 커밋됨 → **`user_hold_quota.held_seats = 3`**, `1A`·`1C` 는 `AVAILABLE`, `HELD` 좌석은 `USER_B` 의 1석뿐 |
| 대조 | 같은 조건에서 올바른 구현은 `SeatUnavailableException` 을 전파하고 quota 행이 남지 않는다 (§8 두 번째 테스트) |

**왜 §4 가 아니라 별도의 §8 인가.** §4 는 올바른 구현을 검증한다. 거기에 잘못된 구현을 넣으면
`assertThatThrownBy` 가 **먼저** 실패하므로 그 뒤의 DB 상태 어서션에 **도달하지 못한다.**
즉 §4 에서 얻는 RED 는 **"예외가 나오지 않았다" 까지**이고,
**"좌석 없이 quota 만 남았다" 는 그것과 다른 주장**이라 따로 관측해야 한다.
§8 이 트랜잭션 커밋 **후** 두 값을 각각 확인한다.

**메커니즘** (구조적 논증): 좌석의 부분 갱신은 저장소의 `PROPAGATION_NESTED` savepoint 가
되돌리지만, savepoint 롤백은 바깥 트랜잭션을 rollback-only 로 만들지 않는다.
그래서 같은 트랜잭션의 quota 증가는 그대로 커밋된다.

**운영 서비스에는 이 분기를 넣지 않았다.** 대조군은 테스트 안에만 있다.

### RED 2 — 카운터 행을 읽고 판단 후 증가 (**동시 실행에서 관측한 결과**, 기록을 정정했다)

| 항목 | 값 |
|---|---|
| 변경한 것 | `JdbcUserHoldQuotaRepository.tryAcquire` 의 조건부 UPDATE →<br>`SELECT held_seats` 로 읽고 판단한 뒤 `UPDATE ... SET held_seats = ?` (규칙 8 위반) |
| **유지한 방어** | `ensureRow` 의 `INSERT ... ON DUPLICATE KEY UPDATE` **행 잠금**, V6 `CHECK (0 ≤ held_seats ≤ 4)`, `innodb_lock_wait_timeout = 3` — **어느 것도 제거하지 않았다** |
| 격리 수준 | 커넥션 기본값(MySQL 8.4 = `REPEATABLE READ`). 테스트가 따로 지정하지 않는다 |
| 초기 데이터 | 좌석 6개, `user_hold_quota` 비어 있음 |
| 동기화 위치 | `CountDownLatch` 하나, **서비스 호출 직전**. 잠금 획득 후 배리어는 두지 않는다 |
| **결정적이지 않은 이유** | 위 래치는 두 스레드의 <b>출발</b>만 맞춘다. 각 트랜잭션의 **읽기 스냅숏 생성 시점과 상대 커밋의 선후**는 고정하지 않으므로, 아래 관측은 이 실행에서 일어난 한 가지 교차 순서다 |
| 실행 명령 | `./gradlew :apps:reservation-service:test --tests "*HoldSeatsServiceIntegrationTest" --rerun-tasks` |

**이 실행에서 관측한 것 — 6석이 아니었다.** (한 번 실행한 결과다.)

```
[RED2] tryAcquire read current=0 seats=3 thread=pool-2-thread-1
[RED2] tryAcquire wrote 3
→ 스레드 1: 성공 (3석)
→ 스레드 2: org.springframework.dao.EmptyResultDataAccessException
             Incorrect result size: expected 1, actual 0
→ §5 실패: java.util.concurrent.ExecutionException 으로 Future 회수 단계에서 드러남
```

이 실행에서는 두 번째 트랜잭션의 **잠금 없는 `SELECT` 가 행을 보지 못했다.** `ensureRow` 는
행 잠금을 기다렸다가 통과했지만, `REPEATABLE READ` 의 일관된 읽기 스냅숏이 상대 트랜잭션의
커밋보다 앞서 잡혀 있었기 때문이다 (구조적 논증). **쓰기는 행을 보는데 읽기는 못 보는** 상태다.

**`EmptyResultDataAccessException` 이 모든 실행에서 나온다고 단정하지 않는다.** 두 번째
스레드의 첫 읽기가 상대 커밋 **뒤에** 이뤄지는 순서도 가능하고, 그 경우 `SELECT` 는 3 을 읽어
`LIMIT_EXCEEDED` 를 돌려주므로 §5 는 그 실행에서 **통과할 수도 있다.** 즉 §5 는 이 순진한
구현을 **항상** 잡아내는 테스트가 아니다. 결정적으로 잡으려면 스냅숏과 커밋 순서를 고정하는
동기화 장치가 필요한데, 이번 Task 에서는 그것을 추가하지 않았다.

**정정.** "두 요청 모두 성공해 6석" 은 **이번 조건에서 관측되지 않았다.** 이전 판의 그 기록은
잘못이었고 위 관측으로 대체했다. 이 실행에서 6석을 막은 것은 조건부 UPDATE 하나가 아니라
**`ensureRow` 의 행 잠금이 두 트랜잭션을 직렬화한 것**이 함께 작용한 결과다.

> **6석은 다른 실험의 결과다.** `UserHoldQuotaRaceReproductionTest`
> (`modules/reservation-infra`, Task 2G)가 **카운터 행을 아예 쓰지 않고
> `SELECT COUNT(*) FROM seat_inventory` 로 검사하는** 구현에서 6석을 관측했다.
> 그것은 저장소 계층의 실험이며 **이번 서비스에서 재현한 것이 아니다.**
> 적용 범위가 다르므로 여기에 옮겨 적지 않는다.

**이 RED 가 보여 주는 것과 못 보여 주는 것.** 보여 주는 것은 "§5 가 이 순진한 구현을
**이 실행에서** 탐지했다" 이다. 보여 주지 못하는 것은 두 가지다 — "§5 가 그것을 항상 탐지한다"
(순서에 좌우된다), 그리고 "조건부 UPDATE 만이 6석을 막는다" (후자는 위 2G 실험이 다룬다).

### RED 3 — `@Transactional(REQUIRES_NEW)` (결정적 재현)

| 항목 | 값 |
|---|---|
| 실패한 테스트 | **1개** — §6 `외부_롤백이_quota_와_좌석을_모두_되돌린다` |
| 실패 원인 | `AssertionFailedError: [★ quota 행 확보까지 되돌아간다]` — 서비스가 독립 트랜잭션으로 커밋해 외부 롤백이 닿지 않았다 |

### RED 4 — `@Transactional` 제거 (결정적 재현)

| 항목 | 값 |
|---|---|
| 실패한 테스트 | **10개** — §1(3) §3(2) §4(2) §5(1) §8(2) |
| 실패 원인 | **전부 같다.** `IllegalStateException: ensureRow 는 이 저장소의 DataSource 에 열린 트랜잭션 안에서만 호출할 수 있다` |

**경계가 없으면 조용히 autocommit 되는 것이 아니라 거부된다.**
Task 2F·2G-G 가 만든 참여 검사가 여기서 실제로 작동한다.
**살아남은 §2·§6·§7 의 이유는 각각 다르다** (코드와 대조했다).

| 절 | 통과 이유 |
|---|---|
| §2 (3) | 한 테스트는 **명령 생성 단계**에서 `IllegalArgumentException` 으로 거절된다. 나머지 둘(없는 좌석·회차 혼합)은 **회차 조회 단계**에서 `SaleEventScopeException` 으로 거절된다 — `JdbcSaleEventScopeRepository.resolve` 는 참여 검사 없이 자동커밋 읽기로 실행되므로 트랜잭션이 없어도 정상 동작하고, 그 다음 단계인 `ensureRow` 에 도달하기 전에 끝난다 |
| §6 (1) | 테스트가 **외부 `TransactionTemplate` 으로 경계를 연다.** 서비스에 `@Transactional` 이 없어도 저장소들은 그 외부 경계에 참여한다 |
| §7 (1) | **트랜잭션 없는 호출이 거절되는 것을 기대**하는 테스트다. `@Transactional` 을 지워도 손으로 조립한 서비스는 여전히 `IllegalStateException` 을 받으므로 기대와 일치한다 |

**§8 두 개가 실패한 이유도 정확히 적는다.** 대조군 자체는 자체 `TransactionTemplate` 으로
경계를 열므로 그 부분은 문제가 없다. 실패 지점은 **준비 단계의 `service.hold(USER_B, …)`** 다 —
좌석 `1B` 를 미리 선점당한 상태로 만드는 그 호출이 `@Transactional` 없이 `ensureRow` 에서
거절된다.

### 재현 방법

RED 1 은 **상시 테스트**(§8)라 아래 명령만으로 재현된다.

```bash
./gradlew :apps:reservation-service:test --tests "*HoldSeatsServiceIntegrationTest"
```

RED 2·3·4 는 운영 코드를 일시적으로 바꿔야 관측된다. 변경 지점과 원복 후 확인은 다음과 같다.

| RED | 변경 파일 | 변경 내용 |
|---|---|---|
| 2 | `JdbcUserHoldQuotaRepository.tryAcquire` | 조건부 UPDATE → `SELECT` 후 `UPDATE` |
| 3 | `HoldSeatsService.hold` | `@Transactional` → `@Transactional(propagation = REQUIRES_NEW)` |
| 4 | `HoldSeatsService.hold` | `@Transactional` 제거 |

세 경우 모두 **원복 후 전체 테스트가 통과하는 것까지 확인했다.**
운영 코드에는 순진한 구현이나 테스트용 분기가 남아 있지 않다.

---

## 8. 남은 위험과 후속 범위

### ★ 운영 활성화 전에 반드시 필요한 것

| 항목 | 상태 |
|---|---|
| **확정 경로의 quota 감소** | **없다.** 2G-C 의 미해결 항목 그대로 |
| **자발적 해제 경로의 quota 감소** | **없다.** 저장소 CAS 는 있으나 quota 미연동 |
| **만료 경로의 quota 감소** | **없다.** 스위퍼도 테스트 전용이며 `@Scheduled` 배선이 없다. 2G-F 의 다중 실행 계약을 그대로 따를 예정이다 |
| **멱등키**(규칙 14~17) | 없다. **핵심 한계는 최초 성공 응답을 그대로 재생하지 못한다는 것**이다 — 아래 설명 |
| **감사 로그**(규칙 32) | `seat_state_log` 테이블과 기록 로직이 없다 |
| **Micrometer 메트릭**(규칙 35) | 선점 성공/상한 초과/경합 계측이 없다 |
| **REST API** | 없다. 이 서비스를 부르는 진입점이 없다 |
| **기존 데이터 backfill** | 절차만 있다 (2G-G §4). 수행하지 않았다 |

**세 이탈 경로 중 하나라도 빠지면 카운터가 한 방향으로만 커진다.**
셋을 함께 연동하기 전에는 운영에 켜면 안 된다.

### 멱등키가 없어서 실제로 생기는 문제

**"재요청이 두 번 선점한다" 는 단정은 정확하지 않다.** 같은 좌석을 다시 요청하면
좌석의 조건부 UPDATE(`status = 'AVAILABLE'` 조건)가 걸러 `SeatUnavailableException` 이 되고,
다른 좌석을 요청해도 quota 상한 검사에 걸릴 수 있다. 즉 **상한을 넘는 이중 선점은
기존 방어가 상당 부분 막는다.**

실제 한계는 다른 곳이다.

- **최초 성공 응답을 그대로 재생하지 못한다** (규칙 17). 네트워크 타임아웃 뒤 같은 요청을
  다시 보내면 첫 요청이 성공했는지 알 수 없고, 두 번째 요청은 **자신의 홀드 때문에**
  경합 거절(`SeatUnavailableException`) 또는 상한 거절(`QuotaExceededException`)을 받는다.
  사용자는 자기 좌석을 잡아 놓고도 실패를 본다. 이것이 핵심 한계다.
- **재요청이 이중 선점을 만든다고 단정할 수는 없다.** 현재 요청은 `seatIds` 를 명시하므로
  **같은 요청**의 재시도는 같은 좌석을 다시 요구한다. 첫 요청이 성공했다면 그 좌석은 이미
  자신의 `HELD` 이므로 조건부 UPDATE 가 거절하고, quota 도 이미 올라 있어 상한 검사에
  걸릴 수 있다. 다른 좌석 ID 를 보냈다면 그것은 **다른 요청**이지 재시도가 아니다.
- 규칙 14~17 이 요구하는 `Idempotency-Key` 필수화와 리스 기반 3단계 트랜잭션 경계가 없다.
  새 멱등성 정책은 이번에 결정하지 않는다.

### 테스트 컨테이너를 공유하지 않는 이유

`MySqlSpringTestSupport`(앱)와 `MySqlTestSupport`(`reservation-infra`)는 **각자 컨테이너를
띄운다.** Gradle 이 모듈별로 테스트 JVM 을 나누므로 정적 싱글턴이 공유되지 않기 때문이고,
공유하려면 별도의 장치가 필요하다.

**지금은 공유하지 않는 편이 맞다.** 두 기반 클래스 모두 `@BeforeEach` 에서
`seat_inventory`·`user_hold_quota`·`train_schedule`·`sale_event` 를 지운다.
같은 DB 를 쓰면 한쪽의 초기화가 다른 쪽이 실행 중인 테스트의 데이터를 지워 상호 간섭한다.

**공통 설정 코드의 재사용과 실행 중인 컨테이너의 공유는 다른 문제다.** 전자는 중복을 줄이고,
후자는 격리를 포기한다. 지금 있는 중복은 픽스처 헬퍼 몇 개뿐이라
**측정 없이 테스트 공통 모듈이나 컨테이너 공유 장치를 새로 만들지 않는다.**
컨테이너 기동 시간이 실제로 문제가 되면 그때 측정하고 판단한다.

### 검증하지 않은 것

- **경합 상황의 실제 잠금 보유 시간** — `innodb_lock_wait_timeout` 이 3초로 설정된 것은
  확인했지만, 그 안에 끝나는지는 측정하지 않았다
- **데드락 부재** — quota → seat 순서를 지켰고 동시성 테스트에서 관측되지 않았지만,
  그것은 보조 근거이지 증명이 아니다
- **여러 사용자·여러 회차가 섞인 대규모 경합** — 같은 사용자 2개 요청까지만 측정했다
- **카운터 행 병목** — 같은 사용자의 요청은 한 행에서 직렬화된다. 비용을 측정하지 않았다
- **`REQUIRES_NEW` 가 아닌 다른 전파 속성의 조합** — `REQUIRED` 만 검증했다
- **트랜잭션 타임아웃** — 설정하지 않았다

### Task 2H-B 이후

1. **세 이탈 경로의 quota 감소**를 각각 같은 트랜잭션 경계에서 연동
2. **다중 실행 계약을 유지한** 만료 스위퍼의 `@Scheduled` 배선 —
   [TASK-002G-F](TASK-002G-F-expiry-sweeper-operating-contract.md) 실험 D 가
   **잡 락 없이도 정합성이 유지된다**고 채택했다. 정합성의 근거는 조건부 좌석 UPDATE 의
   실제 `affected_rows` 와 같은 트랜잭션의 quota 감소이지 단일 실행이 아니다.
   ShedLock 등 단일 실행 강제는 **중복 실행 비율과 DB 부하를 측정한 뒤 판단할
   효율 최적화 후보**이며, 잡 락 자체가 "그것이 죽으면 스위퍼가 멈춘다" 는
   새 가용성 위험을 만든다. 이번 Task 에서 스케줄러도 잡 락도 구현하지 않았다
3. 멱등키 저장소(리스 방식, 3단계 트랜잭션 경계)
4. REST API 와 HTTP 상태 코드 결정
5. 감사 로그와 메트릭

---

## 9. 재현 명령

```bash
./gradlew :apps:reservation-service:test --tests "*HoldSeatsServiceTest"
./gradlew :apps:reservation-service:test --tests "*HoldSeatsServiceIntegrationTest"
./gradlew :apps:reservation-service:test --tests "*ReservationServiceApplicationTest"
./gradlew check --rerun-tasks
```
