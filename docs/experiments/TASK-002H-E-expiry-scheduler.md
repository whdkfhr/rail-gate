# TASK-002H-E — 만료 배치 스케줄러 배선

> 관련: [INVARIANTS.md](../INVARIANTS.md) I-10·I-11·I-12,
> [TASK-002G-F](TASK-002G-F-expiry-sweeper-operating-contract.md) — 운영 계약(다중 스위퍼·잡 락),
> [TASK-002G-G](TASK-002G-G-user-hold-quota.md) §4 — 활성화 전 backfill 절차,
> [TASK-002H-D](TASK-002H-D-expiry-application-service.md) — 만료 배치 서비스와 continuation 계약

## 1. 목적과 범위

2H-D 의 `ExpireHoldsService` 는 **아무도 부르지 않았다.** 이 Task 는 그것을 주기적으로 부르는
**얇은 어댑터**를 배선한다.

> ### ★ 배선했을 뿐 운영 활성화가 아니다
>
> **`railgate.expiry.sweep.enabled` 는 기본 `false`** 다. 끄여 있으면 스케줄러 빈도,
> 스케줄 등록도, 스레드 풀도 생기지 않는다. **이 Task 에서 켜지 않았고 운영 데이터도 건드리지 않았다.**
>
> 켜기 전에 반드시 마쳐야 하는 것:
> - **기존 데이터 backfill** — [TASK-002G-G](TASK-002G-G-user-hold-quota.md) §4 절차
> - **drift 점검** — `load-test/verify/user_hold_quota_drift.sql` 의 V-7a·V-7b·V-7c
>
> 카운터가 비어 있거나 어긋난 상태에서 켜면 그룹이 실패로 남거나(의도된 동작) 운영자가
> 모르는 사이 drift 가 쌓인다. **이 Task 에서 backfill 이나 데이터 보정을 실행하지 않았다.**

**범위 밖** — REST API, 멱등성, 결제 승인, 프론트엔드, 규칙 32 의 전체 감사 로그,
ShedLock 등 잡 락, 새 라이브러리.

---

## 2. 호출 계약

```java
// 어댑터가 보는 것은 이 포트 하나다. ExpireHoldsService 가 구현한다.
interface ExpirySweeper { ExpirySweepResult sweep(ExpirySweepRequest request); }
```

한 **tick** 의 계약:

| 상황 | 어댑터의 동작 |
|---|---|
| 첫 tick | `startAfter` 없이 — **처음부터** |
| `nextCursor` 가 있다 (예산 소진) | 다음 tick 이 **그 커서로 이어간다** |
| `nextCursor` 가 비었다 (순회 완료) | 다음 tick 은 **처음부터** — 지나친 실패 후보 재확인 |
| 그룹 실패·손상 후보가 있다 | **커서는 그대로 이어간다.** 그것은 그 그룹의 문제이고 탐색 위치와 무관하다 |
| 호출이 예외로 끝났다 | **커서를 전진시키지 않는다.** 다음 tick 이 같은 구간을 다시 본다 |
| 같은 어댑터에 tick 이 겹쳐 들어왔다 | **건너뛴다** (`skipped` 로 집계) |

**한 tick 은 `sweep` 을 정확히 한 번만 부른다.** backlog 를 한 tick 에서 비우는 반복문을 두지
않았다 — 그런 루프는 한 실행 시간을 backlog 크기에 비례하게 만들고 잠금 경합·커넥션 점유가
예측 불가능해진다. 진행은 **여러 tick 을 거쳐** 이뤄진다.

### ★ 커서를 어댑터가 보관하는 것이 서비스의 무상태 계약과 모순되지 않는 이유

`ExpireHoldsService` 는 진행 상태를 갖지 않는다 — 위치를 **인자로 받고** `nextCursor` 로
**돌려준다**. 그 계약에서 위치를 보관하는 것은 **호출자의 몫**이며 이 어댑터가 바로 그 호출자다.
서비스는 여전히 어느 인스턴스가 불러도 같게 동작한다.

보관 범위는 **이 인스턴스의 메모리**(`volatile Optional<ExpiryCursor>`)다.

- **재시작하면 커서가 사라져 처음부터 시작한다.** 손해가 아니라 지나친 실패 후보를 재확인하는
  정상 경로다.
- **여러 인스턴스가 각자 다른 위치를 갖는 것도 문제가 아니다.** 정합성은 커서가 아니라 좌석의
  조건부 UPDATE(`(id, hold_id)`·활성 상태·`expires_at`)와 quota 트랜잭션이 보장한다
  (2G-F 실험 D). **잡 락은 정합성의 필수 조건이 아니며** 도입은 중복 실행 비율을 측정한 뒤
  판단할 최적화다. 한 인스턴스 안의 겹침만 `AtomicBoolean` 으로 막는다.

### ★ 실패 시 재개 정책

커서를 전진시키지 않는 이유는 **어디까지 처리됐는지 알 수 없기 때문**이다. 전진시키면 처리되지
않은 구간을 건너뛴다. 다시 보는 것은 안전하다 — 회수는 조건부 UPDATE 이므로 이미 처리된 좌석은
`affected_rows = 0` 이 된다.

**예외를 스케줄러 밖으로 던지지 않는다.** 이것은 Spring 이 반복 작업을 멈추기 때문이 아니다.
Spring 의 기본 스케줄러(`ThreadPoolTaskScheduler`·`ConcurrentTaskScheduler`)는 별도 `ErrorHandler`
가 없으면 반복 작업의 예외를 **로깅하고 억제**하며(`TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER`,
Spring Framework 7.0.8 소스로 확인) 다음 실행은 그대로 예약된다. 이 동작은 스케줄러 구성에
달려 있다 — 사용자 정의 `ErrorHandler` 는 다르게 처리할 수 있고, JDK `ScheduledExecutorService`
를 직접 쓰면 예외가 난 반복 작업의 이후 실행이 억제된다. 이 Task 는 둘 다 쓰지 않는다.

어댑터가 직접 잡는 것은 **실패 처리를 명시적으로 소유**하기 위한 선택이다. 커서를 전진시키지 않는
재개 정책, `outcome=failure` 계측, 이어가기 여부·요청 크기를 담은 로그를 스케줄러 구성과 무관하게
같게 유지한다. 다만 **인터럽트는 삼키지 않는다** — cause 사슬에서 `InterruptedException` 을 찾으면
플래그를 복원해 종료 요청이 전파되게 한다.

> 단위 테스트는 `tick()` 을 손으로 부르므로 Spring 의 오류 처리를 거치지 않는다. 검증한 것은
> 어댑터가 실패를 직접 처리한다는 계약이며, **Spring 의 예외 처리 동작은 테스트로 검증하지 않았다.**

**그룹 재시도를 바깥에서 다시 구현하지 않았다.** 일시적 실패만 최대 3회 새 트랜잭션으로
재시도하는 것은 서비스의 책임이다 (2H-D §4).

---

## 3. 설정

```yaml
railgate:
  expiry:
    sweep:
      enabled: false        # ★ 기본 비활성
      fixed-delay: 30s      # 직전 실행이 끝난 뒤의 간격
      page-size: 100        # 후보 SELECT 한 번의 LIMIT
      max-pages: 4          # 한 tick 이 읽을 최대 페이지 수
```

| 항목 | 의미 |
|---|---|
| `enabled` | **기본 `false`.** `@ConditionalOnProperty` 로 구성 전체가 묶여 있어 끄면 스케줄 등록도 스레드 풀도 생기지 않는다 |
| `fixed-delay` | `fixedRate` 가 아니다 — 직전 실행이 **끝난 뒤** 간격을 센다. 배치가 느려졌을 때 실행이 밀려 쌓이는 것을 피한다 |
| `page-size` | 후보 SELECT 한 번의 `LIMIT`. **스캔 행 수나 벌크 UPDATE 크기의 상한이 아니다** (2H-D §2) |
| `max-pages` | 한 tick 이 issue 할 후보 SQL 수. 검사 후보 수의 상한은 `page-size × max-pages` |

**켠 경우에만 값을 검증한다.** 어댑터 생성자가 세 값이 모두 양수인지 보고 아니면
`IllegalArgumentException` 으로 **기동을 실패시킨다** — 켠 뒤 런타임에 드러나는 것보다 낫다.
끄여 있으면 잘못된 값이어도 기동을 막지 않는다. 끄는 것이 곧 위험이 되면 안 된다.

**`@EnableScheduling` 을 `@SpringBootApplication` 이 아니라 이 구성에 둔다.** 앱에 붙이면
끄여 있어도 스케줄러 인프라가 항상 올라간다. **도메인과 애플리케이션 서비스에는 Spring 스케줄링
의존성이 없다** — 어댑터와 구성 클래스만 안다.

---

## 4. 관측 범위

기존 Micrometer(actuator 로 이미 들어와 있다)와 SLF4J 를 쓴다. **새 라이브러리를 넣지 않았다.**

| 메트릭 | 태그 | 의미 |
|---|---|---|
| `railgate.expiry.sweep` | `outcome` = `success`/`failure`/`skipped` | tick 결과. `skipped` 는 아래 참고 |
| `railgate.expiry.seats.reclaimed` | 없음 | 실제 회수 좌석 수 |
| `railgate.expiry.groups.failed` | `kind` = `GroupFailureKind` | 실패 그룹 수 |
| `railgate.expiry.candidates.corrupt` | 없음 | 손상 후보 수 |

- **메트릭 태그에 사용자·좌석·홀드 ID 나 예외 메시지를 쓰지 않는다.** 카디널리티가 터지고
  식별자가 지표 저장소로 샌다. 원인 분류(`kind`)만 태그다. 식별자는 로그에만 남는다
- **정상적인 stale 과 회수 0 건을 오류로 집계하지 않는다** — `success` 로 센다.
  회수가 0 이면 `reclaimed` 카운터를 올리지도 않는다
- **그룹 실패는 배치 실패가 아니다** — `groups.failed` 는 오르지만 `sweep{outcome=failure}` 는 오르지 않는다
- 실패 그룹·손상 후보가 있으면 요약 로그가 `WARN`, 없으면 `INFO`
- **`skipped` 는 같은 어댑터에 중첩 호출이 들어와 실행을 건너뛴 횟수다.** `fixedDelay` 등록은
  직전 실행이 **끝난 뒤** 다음 실행을 예약하므로, 실행 시간이 길다는 이유만으로는 겹치지 않고
  `skipped` 도 오르지 않는다. 오르는 것은 같은 빈을 다른 경로(수동 호출, 추가 스케줄 등록)가
  동시에 부를 때다. **배치가 주기를 따라가는지는 이 값으로 알 수 없다** — §7 의 후속 관측 과제

> ### ★ 이것은 규칙 32 의 감사 로그가 아니다
>
> 여기서 남기는 것은 **배치 요약** 한 줄이다. 규칙 32 가 요구하는 것은 **좌석 하나하나의 상태
> 전이**를 `actor`·`reason`·`traceId` 와 함께 `seat_state_log` 에 기록하는 것이며
> **그 테이블도 기록 로직도 아직 없다.** 감사 로그 완료로 읽으면 안 된다.

---

## 5. 검증 결과

> 이 절은 **최초 커밋 `578a570` 당시의 관측**이다. 리뷰 보완 후의 결과는 §5-1 에 따로 적었고,
> 여기의 배선 테스트 설명(5개)은 §5-1 의 배선 테스트(15개)로 대체됐다.

```
git diff HEAD --check                           → clean
./gradlew check --rerun-tasks --max-workers=1   → BUILD SUCCESSFUL (1m 33s)
```

이번 실행의 테스트 결과 XML 기준:

| 모듈 | tests | failures | errors | skipped |
|---|---|---|---|---|
| `modules:reservation-infra` | 560 | 0 | 0 | 0 |
| `apps:reservation-service` | 153 | 0 | 0 | 0 |
| `modules:reservation-domain` | 202 | 0 | 0 | 0 |
| `apps:queue-service` | 1 | 0 | 0 | 0 |
| **합계** | **916** | **0** | **0** | **0** |

이 Task 가 더한 것은 **22개**(단위 15 · 배선 5 · 통합 2)다. 테스트를 제외하거나 어서션을
완화하거나 `check` 의존성을 건드리지 않았다.

> **환경 메모.** 이 머신의 Docker VM 은 2.07 GB 이고 다른 프로젝트 컨테이너 5개가 상시
> 실행 중이라, 그 상태로는 `reservation-infra` 의 데이터 볼륨 실험에서 MySQL Testcontainer 가
> 종료된다 (2H-D §9 의 관측). 이번 실행도 **사용자 승인을 받아** 그 컨테이너를 일시 중지하고
> 돌린 뒤 **즉시 복구**했다. 코드와 무관한 자원 제약이다.

스케줄러 관련 테스트는 다음과 같다.

### 단위 — `ExpirySweepSchedulerTest` (15개, DB·실제 시간 없음)

`Thread.sleep` 으로 주기를 기다리지 않는다. `tick()` 을 손으로 부르고, 중첩만
`CountDownLatch` 로 결정적으로 만든다.

| 절 | 검증 |
|---|---|
| §1 커서 (5) | 첫 tick 은 처음부터 / `nextCursor` 를 다음 tick 이 이어받음 / 순회 완료 후 초기화 / **한 tick 은 sweep 한 번** / 실패 그룹·손상 후보가 있어도 이어감 |
| §2 예외 (3) | **예외 시 커서 보존** / 예외를 밖으로 던지지 않고 다음 tick 생존 / 인터럽트 플래그 복원 |
| §3 중첩 (1) | 겹친 tick 은 건너뛰고 `skipped` 집계 |
| §4 설정 (4) | 기본 비활성이면 tick 이 no-op / 활성 시 `pageSize`·`maxPages`·`fixedDelay` 거부 / **비활성이면 잘못된 값이어도 기동 허용** |
| §5 관측 (3) | 회수 0 건은 성공 / 회수·실패 그룹·손상 후보 각각 집계 / **태그에 식별자·예외 메시지 없음** |

### 배선 — `ExpirySchedulerWiringTest` (5개, 실제 Spring 컨텍스트)

- **기본 설정에서는 스케줄러 빈도 스케줄 래퍼도 없다** — 다른 테스트에서 우연히 돌 수 없다는
  사실 자체가 검증 항목이다. 운영 코드에 테스트 전용 훅을 넣지 않았다
- 서비스는 배선돼 있고 `ExpirySweeper` 포트를 만족한다
- `enabled=true` 면 어댑터와 스케줄 래퍼가 등록되고 설정값이 그대로 주입된다
- 켠 상태의 잘못된 값은 생성 단계에서 거부된다

### 통합 — `ExpirySchedulerProgressIntegrationTest` (2개, 실제 MySQL)

- ★★ **실패 후보 5석(예산 1×2=2 보다 많다) + 그 뒤 정상 좌석 1석** — 한 tick 으로는 도달하지
  못하고(`HELD` 확인), tick 을 반복하면 유한한 횟수 안에 회수된다. 실패 후보 데이터는 보존되고
  quota 행도 만들어지지 않으며, `sweep{outcome=failure}` 는 0 이다
- 순회 종료 후 다음 tick 은 처음부터 보며, 카운터를 고쳐 주면 지나쳤던 실패 후보를 회수한다
  (고치는 것은 테스트 픽스처다 — **이 Task 는 backfill 을 실행하지 않는다**)

실제 시간을 기다리지 않는다 — 스케줄 등록은 Spring 의 몫이고 검증 대상은 tick 사이의 커서 이어가기다.

### 5-1. 리뷰 보완 후 (PR #26 리뷰 지적 반영)

**[P2] 활성화 배선 테스트가 실제로 만료 서비스를 불렀다.** 이전 §2 는 `fixed-delay=1h` 로
"돌지 않게" 했지만, `@Scheduled` 에 `initialDelay` 가 없으면 Spring 7.0.8 은 초기 지연을 0 으로
보고 등록 시각을 시작 시각으로 넘긴다(`ScheduledAnnotationBeanPostProcessor` →
`ScheduledTaskRegistrar.scheduleFixedDelayTask`). `fixed-delay` 는 실행 **사이의** 간격이라 첫 실행은
즉시 일어나고, 실제로 `scheduling-1` 스레드가 실제 만료 서비스를 부른 로그가 확인됐다. 공유 MySQL 의
테이블 초기화·픽스처와 경쟁할 수 있었다.

**보완** — `ExpirySchedulerWiringTest` 를 다시 짰다. 운영 코드는 바꾸지 않았다.

- **§1 실제 앱 컨텍스트(MySQL)** — 기본값에서는 어댑터·래퍼 빈이 없고, `@EnableScheduling` 의
  등록 처리기 빈도 없으며, `ScheduledTaskHolder` 가 보고하는 작업이 0개다. 서비스는 포트를 만족한다
- **§2~§4 경량 컨텍스트(DB 없음)** — `ApplicationContextRunner` 에 운영 `ExpirySchedulerConfig` 를
  그대로 올리고, 가짜 `ExpirySweeper` 와 **등록을 포착만 하고 실행하지 않는 `TaskScheduler`**
  (`taskScheduler` 이름으로 등록)를 함께 준다. `@ConditionalOnProperty`·`@EnableScheduling`·`@Scheduled`
  등록 경로는 운영과 같다. 포착 스케줄러는 실행기·스레드·타이머를 갖지 않으며, 반환하는
  `ScheduledFuture` 는 Spring 이 종료 시 부르는 `cancel` 과 `getDelay`·`isCancelled` 를 지원한다

| 절 | 검증 |
|---|---|
| §2 활성화 (6) | 어댑터·래퍼 등록 / 설정값이 실제 바인딩으로 주입 / **fixedDelay 작업 정확히 1개, 주기 45s, 시작 시각 ≤ 등록 시각(=실제라면 즉시 실행), 서비스 호출 0회** / 컨텍스트의 `TaskScheduler` 는 포착용 하나뿐이고 `ScheduledExecutorService` 없음 / **포착한 작업을 명시적으로 부르면 tick 으로 전달**(요청 크기·커서·success 계측) / **컨텍스트 종료 시 Spring 이 등록 작업을 취소** |
| §3 비활성 (3) | 속성 없음·`enabled=false`·끈 상태의 잘못된 값 — 모두 기동하고, 등록 처리기 없음, 포착된 등록 0개, 서비스 호출 0회 |
| §4 잘못된 설정 (4) | `page-size=0`·`max-pages=-1`·`fixed-delay=0s` 는 **실제 프로퍼티 바인딩 후 컨텍스트 시작 실패**(원인 `IllegalArgumentException`) / `fixed-delay=not-a-duration` 은 바인딩 실패(`BindException`) |

**테스트 수가 바뀐 이유** — 배선 테스트 5개 → 15개(+10). 이전 §2(전체 컨텍스트로 활성화, 2개)는
자동 실행이 일어나므로 제거했고, 이전 §3(생성자 직접 호출, 1개)은 컨텍스트 시작 실패 검증 4개로
바꿨다. 같은 생성자 검증은 단위 테스트 §4 에 그대로 있다. 단위 15개·MySQL 진행 보장 2개는 바꾸지
않았다 — 진행 보장 테스트는 원래 어댑터를 직접 만들어 `tick()` 을 손으로 부른다.

```
git diff HEAD --check                           → clean
./gradlew check --rerun-tasks --max-workers=1   → BUILD SUCCESSFUL (1m 45s)
```

| 모듈 | tests | failures | errors | skipped |
|---|---|---|---|---|
| `modules:reservation-infra` | 560 | 0 | 0 | 0 |
| `apps:reservation-service` | 163 | 0 | 0 | 0 |
| `modules:reservation-domain` | 202 | 0 | 0 | 0 |
| `apps:queue-service` | 1 | 0 | 0 | 0 |
| **합계** | **926** | **0** | **0** | **0** |

`apps:reservation-service` 의 테스트 결과 XML 에서 `scheduling-` 스레드 로그는 **0건**이다.
어댑터 로그는 전부 `Test worker`(손으로 부른 tick) 또는 단위 테스트의 중첩 검증용 풀
스레드에서 나왔다. 이번 실행은 다른 프로젝트 컨테이너를 멈추지 않은 상태에서 통과했다.

**[P3] 문구 정정** — 예외 처리 설명(§2)과 `skipped` 의미(§4·§7)를 고쳤다. 정상 예외 처리 로직은
바꾸지 않았다.

---

## 6. ★ 먼저 실패시킨 기록 (규칙 27)

어댑터를 임시로 바꿔 관측한 뒤 원복했고, 원복 후 파일이 바이트 동일함을 확인했다.

| # | 순진한 구현 | 실패 | 관측 |
|---|---|---|---|
| **RED A** | `resumeFrom` 를 항상 비움 (매 tick 처음부터) | **5개** | 커서 전달·순회 초기화·실패 중 진행·예외 보존이 모두 깨진다. 통합 테스트에서 정상 좌석이 끝내 회수되지 않는다 |
| **RED B** | 예외에도 커서를 전진 | **1개** | `예외가_나면_커서를_전진시키지_않는다` — 처리되지 않은 구간을 건너뛴다 |
| **RED C** | 실행 중첩 허용 | **1개** | 겹친 tick 이 `sweep` 을 두 번 불렀다 |
| **RED D** | 한 tick 에서 `do { } while (hasMore)` 로 backlog 비움 | **3개** | `한_tick_은_sweep_을_한_번만_부른다` 와 커서 이어가기 어서션이 깨진다 |

RED A 가 이번 Task 의 핵심이다 — **커서를 이어가지 않으면 스케줄러가 있어도 깊은 실패 블록
뒤에는 영원히 도달하지 못한다.**

---

## 7. 남은 위험

| 항목 | 상태 |
|---|---|
| **운영 활성화** | **하지 않았다.** `enabled=false` 이며 backfill·drift 점검이 선행 조건이다 |
| **규칙 32 감사 로그** | 없다. 배치 요약 로그와 다르다 |
| **잡 락(ShedLock)** | 없다. 정합성에는 불필요하며(2G-F 실험 D) 중복 실행 비율·DB 부하를 측정한 뒤 판단 |
| **`fixed-delay`·`page-size`·`max-pages` 기본값** | **측정 근거가 없다.** 운영 backlog 와 경합을 보고 조정해야 한다 |
| **다중 인스턴스의 중복 작업량** | 정합성은 보장되지만 헛일의 양을 측정하지 않았다 |
| **REST API·멱등성·결제 승인(I-15)** | 범위 밖 |

- **스케줄러 스레드 풀 크기를 조정하지 않았다.** Spring 기본값(단일 스레드)을 쓰며,
  다른 `@Scheduled` 작업이 추가되면 서로 밀릴 수 있다. 지금은 이 작업뿐이다
- **배치가 주기를 따라가는지 볼 지표가 없다.** `skipped` 는 그 신호가 아니다(§4).
  실행 시간, 마지막 성공 시각, backlog·만료 지연 측정은 **후속 관측 과제**로 남긴다.
  이번 보완에서 새 지표나 알람을 만들지 않았다
- 손상 후보와 실패 그룹은 메트릭·로그로만 보이고 **조치 절차가 없다**

---

## 8. 재현 명령

```bash
./gradlew :apps:reservation-service:test --tests "*ExpirySweepSchedulerTest"
./gradlew :apps:reservation-service:test --tests "*ExpirySchedulerWiringTest*"   # DB 없는 §2~§4 + MySQL §1
./gradlew :apps:reservation-service:test --tests "*ExpirySchedulerProgressIntegrationTest"
./gradlew check --rerun-tasks --max-workers=1
```
