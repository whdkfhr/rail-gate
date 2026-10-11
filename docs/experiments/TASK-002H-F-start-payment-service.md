# TASK-002H-F — 단일 좌석 결제 시작 애플리케이션 서비스

> 관련: [REQUIREMENTS.md](../REQUIREMENTS.md) P-4, [INVARIANTS.md](../INVARIANTS.md) I-8·I-10·I-11·I-12·I-15,
> [TASK-002B](TASK-002B-seat-confirm-race.md) — 결제 시작·확정 저장소,
> [TASK-002F](TASK-002F-transaction-boundary.md) — 트랜잭션 경계,
> [TASK-002H-C](TASK-002H-C-confirmation-application-service.md) — 확정 서비스,
> [TASK-002H-D](TASK-002H-D-expiry-application-service.md) — 만료 배치 서비스,
> [TASK-002H-E](TASK-002H-E-expiry-scheduler.md) — 만료 스케줄러(기본 비활성)

## 1. 목적과 범위

기존 저장소의 `JdbcSeatPaymentRepository.startPayment(SeatId, HoldId)` 를 **애플리케이션 서비스로
연결한다.** 선점(2H-A)·해제(2H-B)·확정(2H-C)·만료(2H-D) 다음으로, 지금까지 저장소를 직접 불러야 했던
`HELD → PAYING` 전이에 서비스 경계를 붙인다.

> ### ★ 결제를 요청하거나 승인하는 것이 아니다
>
> 이 서비스는 좌석을 결제 단계로 넘기고 만료 시각을 결제 창만큼 보장할 뿐이다. PG 를 부르지 않고,
> `payment` 테이블도 없으며, **결제 승인 검증(I-15)은 여전히 없다.**

**한 것**

- 도메인 포트 `SeatPaymentPort` 추가, 기존 `JdbcSeatPaymentRepository` 가 구현
- `SeatPaymentOutcome` 을 `reservation-infra` → `reservation-domain`(`com.railgate.reservation.hold`)으로 **이동** (복제 없음)
- `StartPaymentCommand(SeatId, HoldId)` 와 `StartPaymentService` (`@Transactional`, `REQUIRED`)
- 결제 시작·확정 포트를 **하나의 어댑터 인스턴스**로 배선
- 오래된 주석 정리 — "확정·만료 경로 quota 감소 미연동" 서술(서비스 2곳·저장소 2곳·테스트 픽스처 주석)

**하지 않은 것** — 다좌석 결제 시작·확정, REST API, 인증·인가, 멱등키 저장소, PG 호출, I-15,
감사 로그(규칙 32), 메트릭(규칙 35), migration, 새 인덱스, 만료 스케줄러 활성화(`enabled=false` 유지),
운영 backfill·데이터 보정.

---

## 2. 결제 시작 계약 — 바꾸지 않았다

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

| 항목 | 내용 |
|---|---|
| 성공 판정 | 이 문장의 `affected_rows` = 1. 사전 조회도 사후 재조회도 없다 (규칙 1) |
| 만료 판단 | `expires_at > NOW(3)` — DB 시각. 애플리케이션 시각을 쓰지 않는다 (규칙 7) |
| 만료 시각 | `GREATEST(기존, NOW(3) + 5분)` — 앞당겨지지 않는다 (P-4) |
| 소유권 | `hold_id`·`held_by`·`held_at` 을 건드리지 않는다. 확정·해제가 다시 확인한다 |
| 재요청 | 이미 `PAYING` 이면 `status='HELD'` 조건에 걸려 0 건 → `NOT_STARTED`. 만료를 재연장하지 않는다 |

`NOT_STARTED` 는 정상 경합 결과다. 서비스는 이를 예외로 바꾸지도, 성공으로 위장하지도 않고
그대로 돌려준다. 원인(HELD 아님·다른 홀드·만료·없는 좌석·이미 PAYING)은 구분하지 않는다 —
구분하려면 추가 조회가 필요하고 그 조회는 또 하나의 창을 만든다.

### ★ `holdId` 는 인증이 아니다

`holdId` 일치는 "지금 이 좌석을 잡고 있는 홀드인가" 만 확인한다. **로그인 사용자가 그 홀드의
소유자인지는 확인하지 않는다.** 홀드 id 를 아는 사람이면 누구든 결제를 시작할 수 있으므로,
API 계층이 생기면 인증된 사용자와 홀드 소유자를 대조해야 한다. 이번 범위가 아니다.

---

## 3. 트랜잭션 경계

`StartPaymentService.start` 는 `@Transactional`(기본 `REQUIRED`)이다. 다른 2H 서비스와 같다.

- 바깥 트랜잭션이 없으면 새로 열고, 있으면 **참여한다.** 바깥이 롤백하면 상태·`expires_at`·`version`
  이 모두 돌아온다 (§5 통합 §3 에서 7개 필드 동등 비교로 확인).
- 트랜잭션 안에 외부 I/O 가 없다 (규칙 10). 문장 하나다.
- 저장소는 `JdbcTemplate` 이므로 Spring 이 묶어 둔 같은 커넥션을 쓴다.

**참여 검사는 없다.** 확정·해제 서비스는 quota 저장소의 참여 검사 덕에 프록시 없이 부르면
`IllegalStateException` 으로 거부되지만, 결제 시작은 quota 를 쓰지 않으므로 그런 검사가 없다.
손으로 `new StartPaymentService(...)` 를 만들면 autocommit 으로 실행된다. 그래도 문장 하나라
부분 반영은 생기지 않으며, 바깥 롤백 참여만 사라진다. 배선된 빈이 프록시인지는 통합 §6 이 확인한다.

---

## 4. ★ quota 를 건드리지 않는 이유와 잠금 순서

### quota 불변

I-12 의 카운터는 **활성 점유**(`HELD` + `PAYING`) 좌석 수다. 결제 시작은 활성 점유 안에서 상태만
바꾸므로 카운터의 참값이 변하지 않는다. 따라서 서비스는 **quota 를 조회하지도 잠그지도 않는다.**
잠그면 같은 사용자의 선점·해제·확정과 불필요하게 직렬화될 뿐 보장하는 것이 없다.
귀속(사용자·회차)도 필요 없으므로 명령에 받지 않는다.

통합 테스트는 정상 시작·NOT_STARTED·외부 롤백·동시 시작 모두에서 카운터 값이 그대로인 것을 확인했다.

### 다른 경로와 잠금이 충돌하지 않는 근거 (구조적 논증)

| 경로 | 잠그는 순서 |
|---|---|
| 선점 (2H-A) | quota → seat(PK 오름차순 벌크) |
| 해제 (2H-B) | quota → seat |
| 확정 (2H-C) | quota → seat |
| 만료 (2H-D) | 그룹마다 quota → seat(PK 오름차순) |
| **결제 시작 (2H-F)** | **seat 1행 (PK 등치)** |

데드락은 둘 이상의 자원을 서로 다른 순서로 잡을 때 생긴다. 결제 시작은 좌석 행 **하나**만 잡고
다른 잠금을 쥔 채 기다리지 않으므로, 상대가 quota 를 쥐고 좌석을 기다리든 좌석을 먼저 잡든
대기 순환을 만들 수 없다. 상대가 먼저 좌석을 잡으면 기다렸다가 갱신된 행을 보고 0 건 또는 1 건으로
끝난다.

**한계** — 호출자가 바깥 트랜잭션에서 다른 행을 이미 잠근 채 이 서비스를 부르면 그 순서는 호출자의
책임이다. 보조 인덱스 항목 잠금까지 포함한 InnoDB 내부 잠금 목록을 실측하지는 않았다.

### 만료와의 상호작용 (I-10·I-11)

- **만료된 홀드** — `expires_at > NOW(3)` 때문에 결제로 넘어가지 않는다. 배치가 회수한다.
- **배치가 후보를 읽은 뒤 결제 시작이 들어오는 경우** — 후보는 이미 만료된 행이므로 결제 시작이 0 건이다.
  통합 §5 가 데코레이터로 이 순서를 고정해 확인했다.
- **결제 시작이 먼저 연장한 뒤 배치가 낡은 후보를 회수하려는 경우** — 회수 UPDATE 가
  `expires_at <= NOW(3)` 를 다시 검사하므로 0 건이다. 저장소 테스트(TASK-002D 계열)가 고정한 계약이며
  이번에 서비스 수준으로 다시 만들지 않았다.

---

## 5. 포트와 배선

```
StartPaymentService ──▶ SeatPaymentPort ◀─┐
                                         ├── JdbcSeatPaymentRepository (빈 하나)
ConfirmSeatService  ──▶ SeatConfirmationPort ◀─┘
```

- `SeatConfirmationPort` 에 결제 시작을 끼워 넣지 않았다. 두 유스케이스는 서로의 메서드를 쓰지 않는다.
- **빈은 하나다.** 포트마다 `new JdbcSeatPaymentRepository(...)` 를 따로 만들면 같은 클래스 인스턴스가 둘
  생기고, Spring 이 생성 후 실제 타입으로 후보를 고르므로 **두 빈 모두 두 포트의 후보**가 되어 주입이
  모호해진다. 그래서 `@Bean JdbcSeatPaymentRepository seatPaymentRepository(...)` 하나만 두었다.
  선언 타입이 구현체인 것은 이 어댑터 하나의 예외이며, 서비스는 여전히 포트로만 주입받는다.
  통합 §6 이 "각 포트 타입의 빈 이름이 하나, 두 포트가 같은 인스턴스" 를 확인한다.
- `DataSource`·트랜잭션 관리자·결제 창(`PAYMENT_DURATION = 5분`)은 기존 것을 재사용했다. 새 설정은 없다.

---

## 6. 검증 결과

### 먼저 실패시킨 기록 (TDD)

`StartPaymentServiceTest` 를 먼저 쓰고, `start` 가 `UnsupportedOperationException` 을 던지는 골격으로
실행했다 — **7개 중 5개 실패**. 통과한 2개는 서비스를 호출하지 않는 명령·생성자 null 검증이다.
구현 후 7개 통과.

이 Task 는 새 동시성 방어를 넣지 않았다 (기존 조건부 UPDATE 를 그대로 호출한다). 그래서 순진한 구현으로
동시성 RED 를 새로 만들지 않았다 — 결제 시작 CAS 의 동시성 근거는 TASK-002B 의 저장소 테스트다.

### 단위 — `StartPaymentServiceTest` (7개, DB 없음)

포트 전달값(같은 seatId·holdId 1회), STARTED·NOT_STARTED 그대로 반환(재시도·추가 조회 없음),
null 명령은 포트 호출 전 거부, 명령 필드·포트 null 거부, 포트 예외를 삼키지 않고 그대로 전파.

### 통합 — `StartPaymentServiceIntegrationTest` (15개, Spring 빈 + MySQL 8.4)

| 절 | 확인한 것 |
|---|---|
| §1 정상 시작 (4) | PAYING 전환, `hold_id`·`held_by`·`held_at` 보존, `version` +1, `expires_at` 비감소, quota 불변 / 잔여 30초면 DB 시각 + 5분 이상으로 연장 / 잔여 30분이면 그대로 / 다른 좌석·사용자 불변 |
| §2 NOT_STARTED (5) | 다른 홀드·만료된 홀드·재요청·없는 좌석·해제된 홀드. 좌석 7개 필드와 quota 그대로, 재요청은 만료 재연장 없음 |
| §3 외부 롤백 (1) | 바깥 안에서는 STARTED·PAYING·연장·version+1 이 보이고, 롤백 후 7개 필드가 시작 직전과 동등 |
| §4 동시 실행 (2) | 같은 좌석 8스레드 동시 시작(래치) → STARTED 1·NOT_STARTED 7, version +1, quota 그대로 / 시작과 해제 경쟁 → 어느 순서든 좌석 AVAILABLE·quota 0 |
| §5 만료 교차 (1) | 배치 후보 조회 직후 결제 시작 → NOT_STARTED, 배치 회수 1, quota 0 |
| §6 배선 (2) | 두 포트 각각 빈 하나·같은 인스턴스, 서비스가 AOP 프록시 |

### 기존 테스트 회귀

`SeatPaymentOutcome` 이동으로 import 만 바뀐 테스트(infra 6개 파일, 앱 2개 파일)는 어서션을 바꾸지 않았고
전체 check 에서 모두 통과했다. 관련 테스트 선실행(앱 결제·확정·해제·만료·애플리케이션 + infra 결제·scope 계약)은
**168개, 실패 0** 이었다.

### 전체 검증

`./gradlew check --rerun-tasks --max-workers=1` — **통과**. 결과는 §9.

### 주장의 층

- **구조적 논증** — quota 불변(활성 점유 안의 전이), 잠금 자원이 1개라 대기 순환 불가.
- **결정적 테스트** — NOT_STARTED 각 원인, 외부 롤백, 만료 배치 교차(데코레이터로 순서 고정), 배선.
- **통계적 관측** — §4 두 테스트. 래치는 출발만 맞추며 어느 분기를 탔는지는 강제하지 않았다.
  "충돌 없음" 은 반증에 실패한 것이지 증명이 아니다.

---

## 7. 남은 위험과 후속 과제

| 항목 | 상태 |
|---|---|
| **인증·인가** | 없다. `holdId` 를 아는 누구든 결제를 시작할 수 있다 |
| **I-15 결제 승인 검증** | 없다. 결제 시작은 결제 요청이 아니다 |
| **멱등키** | 없다. 재요청 `NOT_STARTED` 는 CAS 결과이지 최초 응답 재생이 아니다 |
| **다좌석 결제 시작·확정** | 없다. 홀드 단위 결제 흐름은 후속 |
| **REST API** | 없다. 409 매핑(규칙 21)도 그때 |
| **감사 로그**(규칙 32) | 없다. `HELD → PAYING` 전이가 `seat_state_log` 에 남지 않는다 |
| **메트릭**(규칙 35) | 없다. 결제 시작 성공·NOT_STARTED 계수기를 이번에 추가하지 않았다 |
| **만료 스케줄러** | 기본 비활성 유지. 운영 활성화·backfill 미수행 |

### 검증하지 않은 것

- 결제 시작과 **선점**의 직접 경쟁 — 같은 좌석은 상태가 달라 경쟁하지 않는다는 구조적 논증만 있다
- 결제 시작과 **확정**의 경쟁 — 확정은 `PAYING` 만 대상으로 하므로 결제 시작이 커밋되기 전에는 0 건이다. 서비스 수준 동시 테스트는 두지 않았다
- 만료 직전 시각에서 결제 시작과 배치 회수가 실제로 교차하는 경우 — DB 시각을 조작하지 않고는 결정적으로 만들 수 없어 저장소의 재검사 계약에 기댄다
- 잠금 보유 시간, InnoDB 잠금 목록, 데드락 부재(관측되지 않았을 뿐)

---

## 8. 재현 명령

```bash
./gradlew :apps:reservation-service:test --tests "*StartPaymentServiceTest"
./gradlew :apps:reservation-service:test --tests "*StartPaymentServiceIntegrationTest"
./gradlew check --rerun-tasks --max-workers=1
```

---

## 9. 전체 검증 기록

2026-10-08, `./gradlew check --rerun-tasks --max-workers=1` — `BUILD SUCCESSFUL`, 21 tasks executed.
`verifyArchitectureRules`(도메인 순수성·모듈 경계·버전 직접 명시 금지) 포함. `git diff HEAD --check` 통과.

현재 XML(`build/test-results/test`) 기준:

| 모듈 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `apps:queue-service` | 1 | 0 | 0 | 0 |
| `apps:reservation-service` | 185 | 0 | 0 | 0 |
| `modules:reservation-domain` | 202 | 0 | 0 | 0 |
| `modules:reservation-infra` | 560 | 0 | 0 | 0 |
| **합계** | **948** | **0** | **0** | **0** |

**테스트 수가 바뀐 이유** — TASK-002H-E 기록의 926개에서 +22. 전부 `apps:reservation-service` 의 신규
`StartPaymentServiceTest`(7)와 `StartPaymentServiceIntegrationTest`(15)다. 기존 테스트를 지우거나 제외하지 않았다.

Docker 는 다른 프로젝트 컨테이너 5개가 떠 있는 상태(Docker 메모리 약 2GB)에서 실행했고 OOM 은 없었다.
다른 컨테이너를 멈추거나 Docker 자원을 바꾸지 않았다.
