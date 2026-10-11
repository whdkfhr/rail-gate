package com.railgate.reservation.application.hold.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railgate.reservation.HoldId;
import com.railgate.reservation.UserId;
import com.railgate.reservation.application.hold.HoldSeatsCommand;
import com.railgate.reservation.application.hold.QuotaExceededException;
import com.railgate.reservation.application.hold.idempotency.IdempotencyRecord.State;
import com.railgate.reservation.application.hold.idempotency.StoredHoldOutcome.Rejected;
import com.railgate.reservation.application.hold.idempotency.StoredHoldOutcome.RejectionCode;
import com.railgate.reservation.application.hold.idempotency.StoredHoldOutcome.Succeeded;
import com.railgate.reservation.saleevent.SaleEventId;
import com.railgate.reservation.saleevent.SaleEventScopeException;
import com.railgate.reservation.seat.SeatId;
import com.railgate.reservation.seat.SeatUnavailableException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;

/**
 * Task 2I-A — 선점 멱등성 계약의 순수 부분 (DB 없음).
 *
 * <p>키 비교, fingerprint 정규화, 판정 순서, 저장할 결과의 분류를 고정한다.
 * 장애 구간과 동시성은 {@code HoldIdempotencyFailureWindowTest} 가 MySQL 로 검증한다.
 */
@DisplayName("Task 2I-A — 선점 멱등성 계약 (단위)")
class IdempotencyContractTest {

    private static final UserId USER_A = new UserId(1L);
    private static final UserId USER_B = new UserId(2L);

    private static HoldSeatsCommand command(UserId user, long... seats) {
        return new HoldSeatsCommand(user, Arrays.stream(seats).mapToObj(SeatId::new).toList());
    }

    @Nested
    @DisplayName("§1 키 형식과 비교")
    class 키 {

        @Test
        void UUID_는_유효한_키다() {
            assertThat(new IdempotencyKey("3f2b8c1e-6a0d-4d6f-9f51-2a7c9e0b4d13").value()).hasSize(36);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "short7", " padded-key-1 ", "한글키한글키한글키", "key/with/slash",
                "0123456789012345678901234567890123456789012345678901234567890123x"})
        void 형식이_맞지_않는_키는_거부한다(String raw) {
            assertThatThrownBy(() -> new IdempotencyKey(raw)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 누락된_키는_거부한다() {
            assertThatThrownBy(() -> new IdempotencyKey(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void 대소문자가_다르면_다른_키다() {
            assertThat(new IdempotencyKey("Key-0001")).isNotEqualTo(new IdempotencyKey("key-0001"));
        }

        @Test
        void 사용자가_다르면_같은_키라도_다른_범위다() {
            IdempotencyKey key = new IdempotencyKey("same-key-01");
            assertThat(IdempotencyScope.holdSeats(USER_A, key))
                    .isNotEqualTo(IdempotencyScope.holdSeats(USER_B, key));
        }

        @Test
        void 연산이_다르면_같은_키라도_다른_범위다() {
            IdempotencyKey key = new IdempotencyKey("same-key-01");
            assertThat(IdempotencyScope.holdSeats(USER_A, key))
                    .isNotEqualTo(new IdempotencyScope(USER_A, "RELEASE_HOLD", key));
        }
    }

    @Nested
    @DisplayName("§2 fingerprint 정규화")
    class fingerprint {

        @Test
        void 좌석_순서는_fingerprint_에_영향이_없다() {
            assertThat(HoldRequestFingerprint.of(command(USER_A, 3, 1, 2)))
                    .isEqualTo(HoldRequestFingerprint.of(command(USER_A, 1, 2, 3)));
        }

        @Test
        void 좌석_집합이_다르면_fingerprint_가_다르다() {
            assertThat(HoldRequestFingerprint.of(command(USER_A, 1, 2)))
                    .isNotEqualTo(HoldRequestFingerprint.of(command(USER_A, 1, 3)))
                    .isNotEqualTo(HoldRequestFingerprint.of(command(USER_A, 1)));
        }

        /** 사용자는 범위의 일부다. fingerprint 에 다시 넣지 않는다. */
        @Test
        void 사용자는_fingerprint_에_들어가지_않는다() {
            assertThat(HoldRequestFingerprint.of(command(USER_A, 1, 2)))
                    .isEqualTo(HoldRequestFingerprint.of(command(USER_B, 1, 2)));
        }

        @Test
        void fingerprint_는_SHA256_16진수_64자다() {
            assertThat(HoldRequestFingerprint.of(command(USER_A, 1))).matches("[0-9a-f]{64}");
        }

        /** 중복 좌석은 정규화하지 않는다. 명령이 만들어지지 않으므로 키를 소비하기 전에 400 이다. */
        @Test
        void 중복_좌석_요청은_키_확보_전에_거부된다() {
            assertThatThrownBy(() -> command(USER_A, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("§3 ★ 판정 순서")
    class 판정 {

        private static final String FP = "a".repeat(64);
        private static final String OTHER_FP = "b".repeat(64);
        private static final Succeeded SUCCESS =
                new Succeeded(HoldId.newId(), new SaleEventId(9L), 2);

        @Test
        void 완료된_같은_요청은_재생한다() {
            var record = new IdempotencyRecord(FP, State.COMPLETED, 1, false, SUCCESS);
            assertThat(IdempotencyDecision.decide(record, FP)).isEqualTo(IdempotencyDecision.REPLAY);
        }

        @Test
        void 진행_중이고_리스가_살아_있으면_진행_중이다() {
            var record = new IdempotencyRecord(FP, State.IN_PROGRESS, 1, false, null);
            assertThat(IdempotencyDecision.decide(record, FP)).isEqualTo(IdempotencyDecision.IN_PROGRESS);
        }

        @Test
        void 진행_중이고_리스가_만료되면_인수한다() {
            var record = new IdempotencyRecord(FP, State.IN_PROGRESS, 3, true, null);
            assertThat(IdempotencyDecision.decide(record, FP)).isEqualTo(IdempotencyDecision.TAKE_OVER);
        }

        /** ★ fingerprint 를 상태보다 먼저 본다. 다른 요청은 인수도 재생도 하지 않는다. */
        @Test
        void 다른_요청은_상태와_무관하게_불일치다() {
            List<IdempotencyRecord> records = List.of(
                    new IdempotencyRecord(FP, State.COMPLETED, 1, false, SUCCESS),
                    new IdempotencyRecord(FP, State.IN_PROGRESS, 1, false, null),
                    new IdempotencyRecord(FP, State.IN_PROGRESS, 1, true, null));
            assertThat(records).allSatisfy(record ->
                    assertThat(IdempotencyDecision.decide(record, OTHER_FP))
                            .isEqualTo(IdempotencyDecision.MISMATCH));
        }

        @Test
        void 완료_상태에만_결과가_있다() {
            assertThatThrownBy(() -> new IdempotencyRecord(FP, State.IN_PROGRESS, 1, false, SUCCESS))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new IdempotencyRecord(FP, State.COMPLETED, 1, false, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("§4 ★ 저장할 결과와 저장하지 않을 장애")
    class 결과_분류 {

        @Test
        void 업무_실패는_저장한다() {
            assertThat(StoredHoldOutcome.rejectionOf(new SeatUnavailableException("x")))
                    .contains(new Rejected(RejectionCode.SEAT_UNAVAILABLE));
            assertThat(StoredHoldOutcome.rejectionOf(new QuotaExceededException("x")))
                    .contains(new Rejected(RejectionCode.QUOTA_EXCEEDED));
            assertThat(StoredHoldOutcome.rejectionOf(new SaleEventScopeException("x")))
                    .contains(new Rejected(RejectionCode.SALE_EVENT_SCOPE_REJECTED));
        }

        /** 커밋 여부가 불확실할 수 있는 장애는 결과로 굳히지 않는다. */
        @Test
        void 재시도_가능한_장애와_알_수_없는_예외는_저장하지_않는다() {
            assertThat(StoredHoldOutcome.rejectionOf(new CannotAcquireLockException("lock wait"))).isEmpty();
            assertThat(StoredHoldOutcome.rejectionOf(new IllegalStateException("배선"))).isEmpty();
        }
    }
}
