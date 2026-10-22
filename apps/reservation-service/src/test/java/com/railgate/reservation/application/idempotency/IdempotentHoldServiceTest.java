package com.railgate.reservation.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.application.hold.HoldSeatsCommand;
import com.railgate.reservation.application.hold.HoldSeatsResult;
import com.railgate.reservation.application.hold.QuotaExceededException;
import com.railgate.reservation.idempotency.HoldRejectionCode;
import com.railgate.reservation.idempotency.IdempotencyKey;
import com.railgate.reservation.idempotency.IdempotencyKeyState;
import com.railgate.reservation.idempotency.IdempotencyRecord;
import com.railgate.reservation.idempotency.IdempotencyScope;
import com.railgate.reservation.idempotency.KeyOwnership;
import com.railgate.reservation.idempotency.KeyWriteOutcome;
import com.railgate.reservation.idempotency.RequestFingerprint;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Rejected;
import com.railgate.reservation.idempotency.StoredHoldOutcome.Succeeded;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.saleevent.SaleEventScopeException;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.seat.SeatUnavailableException;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Task 2I-C — 선점 멱등성 조정자의 <b>판정과 트랜잭션 경계</b>를 DB 없이 고정한다.
 *
 * <p>가짜 포트와 가짜 트랜잭션 관리자는 "커밋하면 반영, 롤백하면 소멸" 만 흉내 낸다. 실제 원자성·경합·잠금은
 * {@code IdempotentHoldServiceIntegrationTest} 가 MySQL 로 검증한다.
 */
@DisplayName("Task 2I-C — IdempotentHoldService (단위)")
class IdempotentHoldServiceTest {

    private static final UserId USER = new UserId(7L);
    private static final IdempotencyKey KEY = new IdempotencyKey("unit-key-0001");
    private static final IdempotencyScope SCOPE = IdempotencyScope.holdSeats(USER, KEY);
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final HoldSeatsResult HELD =
            new HoldSeatsResult(HoldId.newId(), new SaleEventId(9L), 2);

    private StagingTransactionManager tx;
    private FakeIdempotencyKeyPort keys;
    private MeterRegistry meters;
    private final AtomicInteger holdCalls = new AtomicInteger();
    private final AtomicInteger committedHolds = new AtomicInteger();
    private final List<String> holdTx = new ArrayList<>();
    private Function<HoldSeatsCommand, HoldSeatsResult> holdBehavior;

    @BeforeEach
    void setUp() {
        tx = new StagingTransactionManager();
        keys = new FakeIdempotencyKeyPort(tx);
        meters = new SimpleMeterRegistry();
        holdBehavior = command -> HELD;
    }

    private IdempotentHoldService service() {
        return new IdempotentHoldService(keys, IdempotentHoldServiceTest.this::fakeHold, tx, LEASE, meters);
    }

    /** 가짜 선점. 좌석 변경을 현재 트랜잭션에 쌓으므로 롤백되면 남지 않는다. */
    private HoldSeatsResult fakeHold(HoldSeatsCommand command) {
        holdCalls.incrementAndGet();
        holdTx.add(String.valueOf(tx.currentTxId()));
        tx.stage(committedHolds::incrementAndGet);
        return holdBehavior.apply(command);
    }

    private static HoldSeatsCommand command(long... seats) {
        return command(USER, seats);
    }

    private static HoldSeatsCommand command(UserId user, long... seats) {
        return new HoldSeatsCommand(user, Arrays.stream(seats).mapToObj(SeatId::new).toList());
    }

    private double count(String name, String... tags) {
        var search = meters.find(name);
        if (tags.length > 0) {
            search = search.tags(tags);
        }
        var counter = search.counter();
        return counter == null ? 0 : counter.count();
    }

    private static Succeeded succeeded() {
        return new Succeeded(HELD.holdId(), HELD.saleEventId(), HELD.seatCount());
    }

    // ------------------------------------------------------------------ fingerprint

    @Nested
    @DisplayName("§1 fingerprint")
    class Fingerprint {

        @Test
        void 버전_접두사와_정렬한_좌석_id_의_SHA256_이다() throws Exception {
            String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest("hold-seats/v1:1,2,30".getBytes(StandardCharsets.US_ASCII)));
            assertThat(HoldRequestFingerprints.of(command(30, 2, 1)).value()).isEqualTo(expected);
        }

        @Test
        void 좌석_순서가_달라도_같고_집합이_다르면_다르다() {
            assertThat(HoldRequestFingerprints.of(command(3, 1, 2)))
                    .isEqualTo(HoldRequestFingerprints.of(command(1, 2, 3)))
                    .isNotEqualTo(HoldRequestFingerprints.of(command(1, 2, 4)));
        }

        /** 숫자 정렬이다. 문자열 정렬이면 [10, 9] 와 [9, 10] 의 정규형이 "10,9" 가 된다. */
        @Test
        void 좌석_id_는_숫자로_정렬한다() throws Exception {
            String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest("hold-seats/v1:9,10".getBytes(StandardCharsets.US_ASCII)));
            assertThat(HoldRequestFingerprints.of(command(10, 9)).value()).isEqualTo(expected);
        }

        @Test
        void 중복_좌석은_명령_생성_단계에서_거부되어_키를_소비하지_않는다() {
            assertThatThrownBy(() -> command(1, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThat(keys.calls()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ 판정

    @Nested
    @DisplayName("§2 판정 순서 — 불일치 → 재생 → 진행 중 → 인수")
    class 판정 {

        private final RequestFingerprint fp = new RequestFingerprint("a".repeat(64));
        private final RequestFingerprint other = new RequestFingerprint("b".repeat(64));
        private final KeyOwnership owner = KeyOwnership.newGeneration();

        private IdempotencyRecord record(IdempotencyKeyState state, boolean expired) {
            return new IdempotencyRecord(fp, state, owner, expired,
                    state == IdempotencyKeyState.COMPLETED ? Optional.of(succeeded()) : Optional.empty());
        }

        @Test
        void 불일치를_상태보다_먼저_본다() {
            assertThat(List.of(record(IdempotencyKeyState.COMPLETED, false),
                    record(IdempotencyKeyState.IN_PROGRESS, false),
                    record(IdempotencyKeyState.IN_PROGRESS, true)))
                    .allSatisfy(r -> assertThat(HoldIdempotencyDecision.decide(r, other))
                            .isEqualTo(HoldIdempotencyDecision.MISMATCH));
        }

        @Test
        void 같은_요청이면_완료는_재생_진행_중은_대기_만료는_인수다() {
            assertThat(HoldIdempotencyDecision.decide(record(IdempotencyKeyState.COMPLETED, false), fp))
                    .isEqualTo(HoldIdempotencyDecision.REPLAY);
            assertThat(HoldIdempotencyDecision.decide(record(IdempotencyKeyState.IN_PROGRESS, false), fp))
                    .isEqualTo(HoldIdempotencyDecision.IN_PROGRESS);
            assertThat(HoldIdempotencyDecision.decide(record(IdempotencyKeyState.IN_PROGRESS, true), fp))
                    .isEqualTo(HoldIdempotencyDecision.TAKE_OVER);
        }
    }

    // ------------------------------------------------------------------ 업무 실패 분류

    @Nested
    @DisplayName("§3 업무 실패 분류 — 계약의 예외만 명시적으로")
    class 분류 {

        @Test
        void 계약의_세_예외만_업무_실패다() {
            assertThat(HoldRejectionClassifier.classify(new SeatUnavailableException("x")))
                    .contains(new Rejected(HoldRejectionCode.SEAT_UNAVAILABLE));
            assertThat(HoldRejectionClassifier.classify(new QuotaExceededException("x")))
                    .contains(new Rejected(HoldRejectionCode.QUOTA_EXCEEDED));
            assertThat(HoldRejectionClassifier.classify(new SaleEventScopeException("x")))
                    .contains(new Rejected(HoldRejectionCode.SALE_EVENT_SCOPE_REJECTED));
        }

        /** 원인 사슬을 뒤지지 않는다. DB 오류에 업무 예외가 원인으로 붙어 있어도 업무 실패가 아니다. */
        @Test
        void DB_오류와_중첩된_원인은_업무_실패가_아니다() {
            assertThat(HoldRejectionClassifier.classify(new CannotAcquireLockException("lock wait"))).isEmpty();
            assertThat(HoldRejectionClassifier.classify(
                    new DataIntegrityViolationException("x", new SeatUnavailableException("nested")))).isEmpty();
            assertThat(HoldRejectionClassifier.classify(
                    new RuntimeException(new QuotaExceededException("nested")))).isEmpty();
            assertThat(HoldRejectionClassifier.classify(new IllegalStateException("배선"))).isEmpty();
        }
    }

    // ------------------------------------------------------------------ 정상 경로

    @Nested
    @DisplayName("§4 실행·재생·불일치·진행 중")
    class 정상_경로 {

        @Test
        void 새_키는_TX0_에서_확보하고_TX1_에서_선점과_성공_기록을_함께_커밋한다() {
            IdempotentHoldResult result = service().hold(KEY, command(1, 2));

            assertThat(result).isEqualTo(new IdempotentHoldResult.Executed(succeeded()));
            assertThat(keys.calls()).containsExactly("tryInsert@1", "completeSucceeded@2");
            assertThat(holdTx).containsExactly("2");
            assertThat(tx.ends()).containsExactly("1:commit", "2:commit");
            assertThat(keys.row(SCOPE).state).isEqualTo(IdempotencyKeyState.COMPLETED);
            assertThat(count("railgate.idempotency.hold.calls", "result", "executed")).isEqualTo(1);
            assertThat(count("railgate.idempotency.hold.attempts")).isEqualTo(1);
            assertThat(count("railgate.idempotency.hold.committed", "outcome", "succeeded")).isEqualTo(1);
        }

        @Test
        void 같은_키_같은_요청은_실행하지_않고_같은_결과를_재생한다() {
            IdempotentHoldService service = service();
            service.hold(KEY, command(1, 2));

            IdempotentHoldResult replay = service.hold(KEY, command(2, 1));

            assertThat(replay).isEqualTo(new IdempotentHoldResult.Replayed(succeeded()));
            assertThat(holdCalls).hasValue(1);
            assertThat(count("railgate.idempotency.hold.calls", "result", "replayed")).isEqualTo(1);
        }

        @Test
        void 같은_키_다른_요청은_실행하지_않고_불일치다() {
            IdempotentHoldService service = service();
            service.hold(KEY, command(1, 2));

            assertThat(service.hold(KEY, command(1, 3))).isEqualTo(new IdempotentHoldResult.FingerprintMismatch());
            assertThat(holdCalls).hasValue(1);
            assertThat(count("railgate.idempotency.hold.calls", "result", "mismatch")).isEqualTo(1);
        }

        @Test
        void 다른_사용자의_같은_키는_별개다() {
            IdempotentHoldService service = service();
            service.hold(KEY, command(1));

            assertThat(service.hold(KEY, command(new UserId(8L), 1)))
                    .isInstanceOf(IdempotentHoldResult.Executed.class);
            assertThat(holdCalls).hasValue(2);
        }

        @Test
        void 리스가_살아_있는_진행_중_키는_실행하지_않는다() {
            putInProgress(KeyOwnership.newGeneration(), false);

            assertThat(service().hold(KEY, command(1, 2))).isEqualTo(new IdempotentHoldResult.InProgress());
            assertThat(holdCalls).hasValue(0);
            assertThat(count("railgate.idempotency.hold.calls", "result", "in_progress")).isEqualTo(1);
        }
    }

    private void putInProgress(KeyOwnership owner, boolean expired) {
        FakeIdempotencyKeyPort.Row row = new FakeIdempotencyKeyPort.Row();
        row.fingerprint = HoldRequestFingerprints.of(command(1, 2));
        row.ownership = owner;
        row.leaseExpired = expired;
        keys.put(SCOPE, row);
    }

    // ------------------------------------------------------------------ 인수와 소유권

    @Nested
    @DisplayName("§5 인수와 소유권 상실")
    class 소유권 {

        @Test
        void 만료된_키는_같은_세대에서_인수하고_인수한_소유권으로_기록한다() {
            KeyOwnership old = KeyOwnership.newGeneration();
            putInProgress(old, true);

            assertThat(service().hold(KEY, command(1, 2))).isInstanceOf(IdempotentHoldResult.Executed.class);
            assertThat(keys.row(SCOPE).ownership).isEqualTo(old.next());
            assertThat(keys.calls()).containsExactly("tryInsert@1", "find@2", "takeOver@3", "completeSucceeded@4");
            assertThat(count("railgate.idempotency.hold.takeovers")).isEqualTo(1);
        }

        /** ★ 성공 기록이 NOT_OWNER 면 TX1 전체를 롤백한다 — 좌석 변경이 남지 않는다. */
        @Test
        void 성공_기록이_거절되면_TX1_을_롤백하고_소유권_상실로_답한다() {
            IdempotentHoldService service = service();
            holdBehavior = command -> {
                // 본 작업 도중 다른 시도가 인수했다 (가짜 포트 행의 fence 를 올린다).
                FakeIdempotencyKeyPort.Row row = keys.row(SCOPE);
                row.ownership = row.ownership.next();
                return HELD;
            };

            assertThat(service.hold(KEY, command(1, 2))).isEqualTo(new IdempotentHoldResult.OwnershipLost());
            assertThat(committedHolds).as("좌석 변경 롤백").hasValue(0);
            assertThat(tx.ends()).containsExactly("1:commit", "2:rollback");
            assertThat(keys.row(SCOPE).state).isEqualTo(IdempotencyKeyState.IN_PROGRESS);
            assertThat(count("railgate.idempotency.hold.not_owner", "write", "succeeded")).isEqualTo(1);
            assertThat(count("railgate.idempotency.hold.calls", "result", "ownership_lost")).isEqualTo(1);
            assertThat(count("railgate.idempotency.hold.committed")).isZero();
        }

        @Test
        void 업무_실패_기록이_거절되면_소유권_상실로_답한다() {
            holdBehavior = command -> {
                FakeIdempotencyKeyPort.Row row = keys.row(SCOPE);
                row.ownership = row.ownership.next();
                throw new SeatUnavailableException("경합");
            };

            assertThat(service().hold(KEY, command(1, 2))).isEqualTo(new IdempotentHoldResult.OwnershipLost());
            assertThat(count("railgate.idempotency.hold.not_owner", "write", "rejected")).isEqualTo(1);
        }

        @Test
        void 조회와_인수_경쟁이_계속되면_유한_횟수_뒤_진행_중으로_답한다() {
            putInProgress(KeyOwnership.newGeneration(), true);
            IdempotentHoldService service = new IdempotentHoldService(
                    new LosingTakeOverPort(keys), IdempotentHoldServiceTest.this::fakeHold, tx, LEASE, meters);

            assertThat(service.hold(KEY, command(1, 2))).isEqualTo(new IdempotentHoldResult.InProgress());
            assertThat(keys.calls().stream().filter(c -> c.startsWith("tryInsert")).count())
                    .isEqualTo(IdempotentHoldService.MAX_ACQUIRE_ROUNDS);
            assertThat(holdCalls).hasValue(0);
        }

        @Test
        void 중복_뒤_행이_사라졌으면_다시_넣어_소유한다() {
            putInProgress(KeyOwnership.newGeneration(), false);
            IdempotentHoldService service = new IdempotentHoldService(
                    new VanishingRowPort(keys, SCOPE), IdempotentHoldServiceTest.this::fakeHold, tx, LEASE, meters);

            assertThat(service.hold(KEY, command(1, 2))).isInstanceOf(IdempotentHoldResult.Executed.class);
        }
    }

    // ------------------------------------------------------------------ 업무 실패

    @Nested
    @DisplayName("§6 업무 실패 — TX1 롤백 확인 뒤 TX2 에 기록")
    class 업무_실패 {

        @Test
        void 업무_실패는_TX1_롤백_뒤_별도_트랜잭션에_기록하고_재생한다() {
            holdBehavior = command -> {
                throw new SeatUnavailableException("경합");
            };
            IdempotentHoldService service = service();

            IdempotentHoldResult first = service.hold(KEY, command(1, 2));

            Rejected rejected = new Rejected(HoldRejectionCode.SEAT_UNAVAILABLE);
            assertThat(first).isEqualTo(new IdempotentHoldResult.Executed(rejected));
            assertThat(tx.ends()).containsExactly("1:commit", "2:rollback", "3:commit");
            assertThat(keys.calls()).containsExactly("tryInsert@1", "completeRejected@3");
            assertThat(committedHolds).hasValue(0);
            assertThat(count("railgate.idempotency.hold.committed", "outcome", "seat_unavailable")).isEqualTo(1);

            holdBehavior = command -> HELD; // 좌석이 비어도
            assertThat(service.hold(KEY, command(1, 2))).isEqualTo(new IdempotentHoldResult.Replayed(rejected));
            assertThat(holdCalls).hasValue(1);
        }
    }

    // ------------------------------------------------------------------ 장애

    @Nested
    @DisplayName("§7 ★ 장애 — 결과를 저장하지 않고 리스를 포기한 뒤 전파")
    class 장애 {

        @Test
        void 재시도_가능한_장애는_같은_소유권으로_리스를_포기하고_그대로_전파한다() {
            CannotAcquireLockException failure = new CannotAcquireLockException("lock wait");
            holdBehavior = command -> {
                throw failure;
            };

            assertThatThrownBy(() -> service().hold(KEY, command(1, 2))).isSameAs(failure);
            assertThat(keys.calls()).containsExactly("tryInsert@1", "abandon:1@3");
            assertThat(keys.row(SCOPE).state).isEqualTo(IdempotencyKeyState.IN_PROGRESS);
            assertThat(keys.row(SCOPE).outcome).isNull();
            assertThat(keys.row(SCOPE).leaseExpired).isTrue();
            assertThat(count("railgate.idempotency.hold.abandons", "outcome", "applied")).isEqualTo(1);
            assertThat(count("railgate.idempotency.hold.calls", "result", "failed")).isEqualTo(1);
        }

        @Test
        void 업무_예외가_원인으로_붙은_DB_오류도_업무_실패로_저장하지_않는다() {
            DataIntegrityViolationException failure =
                    new DataIntegrityViolationException("x", new SeatUnavailableException("nested"));
            holdBehavior = command -> {
                throw failure;
            };

            assertThatThrownBy(() -> service().hold(KEY, command(1, 2))).isSameAs(failure);
            assertThat(keys.row(SCOPE).outcome).isNull();
            assertThat(keys.calls()).noneMatch(c -> c.startsWith("completeRejected"));
        }

        @Test
        void 리스_포기까지_실패하면_원래_장애를_던지고_추가_실패를_함께_남긴다() {
            CannotAcquireLockException failure = new CannotAcquireLockException("lock wait");
            DataRetrievalFailureException abandonFailure = new DataRetrievalFailureException("연결 끊김");
            holdBehavior = command -> {
                throw failure;
            };
            keys.failOn("abandon", () -> abandonFailure);

            assertThatThrownBy(() -> service().hold(KEY, command(1, 2)))
                    .isSameAs(failure)
                    .satisfies(t -> assertThat(t.getSuppressed()).containsExactly(abandonFailure));
            assertThat(count("railgate.idempotency.hold.abandons", "outcome", "failed")).isEqualTo(1);
        }

        @Test
        void TX2_기록이_실패하면_리스를_포기하고_전파한다() {
            DataRetrievalFailureException tx2Failure = new DataRetrievalFailureException("TX2");
            holdBehavior = command -> {
                throw new SeatUnavailableException("경합");
            };
            keys.failOn("completeRejected", () -> tx2Failure);

            assertThatThrownBy(() -> service().hold(KEY, command(1, 2))).isSameAs(tx2Failure);
            assertThat(keys.row(SCOPE).outcome).isNull();
            assertThat(keys.calls()).anyMatch(c -> c.startsWith("abandon"));
        }

        /** ★ 커밋 결과 불명 — 실제로는 반영됐다. 포기는 완료된 행을 바꾸지 못하고, 재요청은 재생한다. */
        @Test
        void 커밋_결과_불명이어도_완료된_행은_포기로_바뀌지_않고_재생된다() {
            tx.failCommitAfterApplying(2); // TX1
            IdempotentHoldService service = service();

            assertThatThrownBy(() -> service.hold(KEY, command(1, 2))).isInstanceOf(TransactionSystemException.class);
            assertThat(committedHolds).as("TX1 은 실제로 반영됐다").hasValue(1);
            assertThat(keys.row(SCOPE).state).isEqualTo(IdempotencyKeyState.COMPLETED);
            assertThat(count("railgate.idempotency.hold.abandons", "outcome", "not_owner")).isEqualTo(1);

            assertThat(service.hold(KEY, command(1, 2))).isEqualTo(new IdempotentHoldResult.Replayed(succeeded()));
            assertThat(holdCalls).hasValue(1);
        }

        /** 키 확보 단계의 실제 DB 장애는 진행 중이나 중복으로 바꾸지 않는다. */
        @Test
        void 키_확보의_DB_장애는_삼키지_않는다() {
            CannotAcquireLockException failure = new CannotAcquireLockException("TX0");
            keys.failOn("tryInsert", () -> failure);

            assertThatThrownBy(() -> service().hold(KEY, command(1, 2))).isSameAs(failure);
            assertThat(holdCalls).hasValue(0);
        }

        /** 프로세스 사망을 뜻하는 Error 는 잡지 않는다. 키는 리스 만료를 기다린다. */
        @Test
        void Error_는_잡지_않고_키를_건드리지_않는다() {
            holdBehavior = command -> {
                throw new AssertionError("simulated crash");
            };

            assertThatThrownBy(() -> service().hold(KEY, command(1, 2))).isInstanceOf(AssertionError.class);
            assertThat(keys.calls()).containsExactly("tryInsert@1");
            assertThat(committedHolds).hasValue(0);
        }
    }

    // ------------------------------------------------------------------ 경계·설정·메트릭

    @Nested
    @DisplayName("§8 경계·설정·메트릭")
    class 경계 {

        @Test
        void 바깥_트랜잭션_안에서_부르면_키를_만들기_전에_거부한다() {
            TransactionTemplate outer = new TransactionTemplate(tx);

            assertThatThrownBy(() -> outer.executeWithoutResult(s -> service().hold(KEY, command(1, 2))))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(keys.calls()).isEmpty();
        }

        @Test
        void 리스는_1ms_이상_밀리초_단위여야_한다() {
            for (Duration bad : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1_500_000))) {
                assertThatThrownBy(() -> new IdempotentHoldService(keys, IdempotentHoldServiceTest.this::fakeHold, tx, bad, meters))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        /** 사용자·키·holdId 같은 고유값을 태그로 쓰지 않는다. 태그 이름은 고정 집합이다. */
        @Test
        void 메트릭_태그에_고유값이_없다() {
            IdempotentHoldService service = service();
            service.hold(KEY, command(1, 2));
            service.hold(KEY, command(1, 2));
            service.hold(KEY, command(1, 3));

            Set<String> tagKeys = meters.getMeters().stream()
                    .map(Meter::getId).flatMap(id -> id.getTags().stream()).map(Tag::getKey)
                    .collect(Collectors.toSet());
            assertThat(tagKeys).isSubsetOf("result", "outcome", "write");
            String allTagValues = meters.getMeters().stream()
                    .flatMap(m -> m.getId().getTags().stream()).map(Tag::getValue)
                    .collect(Collectors.joining(","));
            assertThat(allTagValues).doesNotContain(KEY.value(), HELD.holdId().asString(), "7");
        }
    }

    // ------------------------------------------------------------------ 포트 데코레이터

    /** 인수가 언제나 경쟁에서 지는 포트 — 조회 결과는 계속 "만료된 진행 중" 이다. */
    private static final class LosingTakeOverPort extends DelegatingPort {
        LosingTakeOverPort(FakeIdempotencyKeyPort delegate) {
            super(delegate);
        }

        @Override
        public Optional<KeyOwnership> takeOver(IdempotencyScope scope, KeyOwnership seen, Duration lease) {
            delegate.takeOver(scope, seen.next(), lease); // 기록만 남기고
            return Optional.empty();
        }
    }

    /** 첫 중복 뒤 조회 직전에 행이 지워지는 포트 (보존 기간 삭제 흉내). */
    private static final class VanishingRowPort extends DelegatingPort {
        private final IdempotencyScope scope;
        private boolean vanished;

        VanishingRowPort(FakeIdempotencyKeyPort delegate, IdempotencyScope scope) {
            super(delegate);
            this.scope = scope;
        }

        @Override
        public Optional<IdempotencyRecord> find(IdempotencyScope s) {
            if (!vanished) {
                vanished = true;
                delegate.delete(scope);
            }
            return delegate.find(s);
        }
    }

    private static class DelegatingPort implements com.railgate.reservation.idempotency.IdempotencyKeyPort {
        final FakeIdempotencyKeyPort delegate;

        DelegatingPort(FakeIdempotencyKeyPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<KeyOwnership> tryInsert(IdempotencyScope s, RequestFingerprint f, Duration l) {
            return delegate.tryInsert(s, f, l);
        }

        @Override
        public Optional<IdempotencyRecord> find(IdempotencyScope s) {
            return delegate.find(s);
        }

        @Override
        public Optional<KeyOwnership> takeOver(IdempotencyScope s, KeyOwnership seen, Duration l) {
            return delegate.takeOver(s, seen, l);
        }

        @Override
        public KeyWriteOutcome completeSucceeded(IdempotencyScope s, KeyOwnership m, Succeeded o) {
            return delegate.completeSucceeded(s, m, o);
        }

        @Override
        public KeyWriteOutcome completeRejected(IdempotencyScope s, KeyOwnership m, Rejected o) {
            return delegate.completeRejected(s, m, o);
        }

        @Override
        public KeyWriteOutcome abandon(IdempotencyScope s, KeyOwnership m) {
            return delegate.abandon(s, m);
        }
    }
}
