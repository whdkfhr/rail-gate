package com.railgate.reservation.saleevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 저장된 판매 회차의 복원.
 *
 * <p><b>이 테스트가 지키는 것은 "복원은 전이가 아니다" 라는 경계다.</b>
 * {@code CLOSED} 회차를 만들려고 {@code schedule → open → close} 를 재실행하면
 * {@link SaleEvent#open(Instant)} 의 오픈 시각 검사가 과거 데이터에 현재 규칙을 적용하게 되어
 * <b>읽기가 실패</b>한다. {@code SeatRestoreTest} 와 같은 문제의식이다.
 */
@DisplayName("SaleEvent 복원 - 저장된 회차를 전이 재실행 없이 읽는다")
class SaleEventRestoreTest {

    private static final SaleEventId ID = new SaleEventId(9001L);
    private static final String NAME = "2026년 추석 승차권 1차 판매";
    private static final Instant OPENS_AT = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant CLOSES_AT = Instant.parse("2026-08-10T00:00:00Z");

    private static SaleEventSnapshot snapshotOf(SaleEventStatus status) {
        return new SaleEventSnapshot(ID, NAME, OPENS_AT, CLOSES_AT, status);
    }

    @Nested
    @DisplayName("복원")
    class 복원 {

        @ParameterizedTest(name = "{0} 상태를 그대로 복원한다")
        @EnumSource(SaleEventStatus.class)
        void 모든_상태를_복원할_수_있다(SaleEventStatus status) {
            SaleEvent event = SaleEvent.restore(snapshotOf(status));

            assertThat(event.status()).isEqualTo(status);
            assertThat(event.id()).isEqualTo(ID);
            assertThat(event.name()).isEqualTo(NAME);
            assertThat(event.opensAt()).isEqualTo(OPENS_AT);
            assertThat(event.closesAt()).isEqualTo(CLOSES_AT);
        }

        /**
         * 복원은 오픈 시각을 다시 판정하지 않는다.
         *
         * <p>오픈 시각이 아직 오지 않은 것으로 보이는 데이터라도, 이미 {@code OPEN} 으로
         * 저장돼 있다면 그대로 읽어야 한다. 그렇지 않으면 규칙이 바뀔 때마다
         * 과거 데이터를 읽지 못하게 된다.
         */
        @Test
        void 복원은_오픈_시각을_다시_판정하지_않는다() {
            Instant farFuture = Instant.parse("2099-01-01T00:00:00Z");

            SaleEvent event = SaleEvent.restore(
                    new SaleEventSnapshot(ID, NAME, farFuture, null, SaleEventStatus.OPEN));

            assertThat(event.status()).isEqualTo(SaleEventStatus.OPEN);
            assertThat(event.isOpen()).isTrue();
        }

        @Test
        void 마감_시각이_없는_회차도_복원할_수_있다() {
            SaleEvent event = SaleEvent.restore(
                    new SaleEventSnapshot(ID, NAME, OPENS_AT, null, SaleEventStatus.OPEN));

            assertThat(event.closesAt()).isNull();
        }

        /** 복원된 회차도 살아 있는 애그리거트다. 이후 전이는 정상 규칙을 따른다. */
        @Test
        void 복원된_회차도_전이할_수_있다() {
            SaleEvent event = SaleEvent.restore(snapshotOf(SaleEventStatus.OPEN));

            event.close();

            assertThat(event.status()).isEqualTo(SaleEventStatus.CLOSED);
        }

        @Test
        void 복원된_CLOSED_회차는_다시_열리지_않는다() {
            SaleEvent event = SaleEvent.restore(snapshotOf(SaleEventStatus.CLOSED));

            assertThatThrownBy(() -> event.open(OPENS_AT))
                    .isInstanceOf(SaleEventStateTransitionException.class);
            assertThat(event.status()).isEqualTo(SaleEventStatus.CLOSED);
        }

        @Test
        void 스냅샷이_없으면_복원을_거부한다() {
            assertThatThrownBy(() -> SaleEvent.restore(null))
                    .isInstanceOf(SaleEventRestoreException.class);
        }
    }

    /**
     * 손상된 행 검증.
     *
     * <p>{@link SaleEventRestoreException} 은 {@code IllegalArgumentException} 과 구분된다.
     * 잘못된 사용자 입력이 아니라 <b>저장소에 정합성이 깨진 행이 있다는 신호</b>이기 때문이다.
     */
    @Nested
    @DisplayName("손상된 행")
    class 손상된_행 {

        static Stream<Arguments> 거부되는_스냅샷() {
            return Stream.of(
                    Arguments.of("식별자 없음",
                            (Runnable) () -> new SaleEventSnapshot(
                                    null, NAME, OPENS_AT, CLOSES_AT, SaleEventStatus.OPEN)),
                    Arguments.of("이름 없음",
                            (Runnable) () -> new SaleEventSnapshot(
                                    ID, null, OPENS_AT, CLOSES_AT, SaleEventStatus.OPEN)),
                    Arguments.of("이름이 공백뿐",
                            (Runnable) () -> new SaleEventSnapshot(
                                    ID, "   ", OPENS_AT, CLOSES_AT, SaleEventStatus.OPEN)),
                    Arguments.of("오픈 시각 없음",
                            (Runnable) () -> new SaleEventSnapshot(
                                    ID, NAME, null, CLOSES_AT, SaleEventStatus.OPEN)),
                    Arguments.of("상태 없음",
                            (Runnable) () -> new SaleEventSnapshot(
                                    ID, NAME, OPENS_AT, CLOSES_AT, null)),
                    Arguments.of("마감이 오픈과 같음",
                            (Runnable) () -> new SaleEventSnapshot(
                                    ID, NAME, OPENS_AT, OPENS_AT, SaleEventStatus.OPEN)),
                    Arguments.of("마감이 오픈보다 이름",
                            (Runnable) () -> new SaleEventSnapshot(
                                    ID, NAME, OPENS_AT, OPENS_AT.minusSeconds(1), SaleEventStatus.OPEN)));
        }

        @ParameterizedTest(name = "{0} → 복원 거부")
        @MethodSource("거부되는_스냅샷")
        void 도메인이_만들_수_없는_조합은_거부한다(String label, Runnable creation) {
            assertThatThrownBy(creation::run)
                    .as(label)
                    .isInstanceOf(SaleEventRestoreException.class);
        }

        /**
         * 복원 실패는 사용자 입력 오류와 다른 예외다.
         *
         * <p>{@link SaleEvent#schedule} 은 같은 규칙 위반을
         * {@code IllegalArgumentException}(400) 으로 알리고, 복원은
         * {@link SaleEventRestoreException}(5xx·알람) 으로 알린다.
         * 규칙은 {@code SaleEventRules} 하나를 공유하지만 대응은 다르다.
         */
        @Test
        void 복원_실패와_입력_오류는_다른_예외다() {
            assertThatThrownBy(() -> new SaleEventSnapshot(
                    ID, "  ", OPENS_AT, CLOSES_AT, SaleEventStatus.OPEN))
                    .isInstanceOf(SaleEventRestoreException.class)
                    .isNotInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> SaleEvent.schedule(ID, "  ", OPENS_AT, CLOSES_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .isNotInstanceOf(SaleEventRestoreException.class);
        }

        /** 두 경로가 <b>같은 규칙</b>을 쓰는지 확인한다. 한쪽만 느슨해지면 여기서 깨진다. */
        @Test
        void 두_경로가_같은_기간_규칙을_쓴다() {
            Instant valid = OPENS_AT.plusSeconds(1);

            assertThatCode(() -> SaleEvent.schedule(ID, NAME, OPENS_AT, valid))
                    .doesNotThrowAnyException();
            assertThatCode(() -> new SaleEventSnapshot(
                    ID, NAME, OPENS_AT, valid, SaleEventStatus.SCHEDULED))
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> SaleEvent.schedule(ID, NAME, OPENS_AT, OPENS_AT))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SaleEventSnapshot(
                    ID, NAME, OPENS_AT, OPENS_AT, SaleEventStatus.SCHEDULED))
                    .isInstanceOf(SaleEventRestoreException.class);
        }
    }

    /**
     * 왕복 검증. {@code schedule} 로 만든 회차를 스냅샷으로 옮겼다 복원해도 값이 같아야 한다.
     * 저장소가 생기면 이 왕복이 곧 저장·조회 경로가 된다.
     */
    @Test
    void 생성한_회차를_스냅샷으로_왕복해도_값이_같다() {
        SaleEvent origin = SaleEvent.schedule(ID, NAME, OPENS_AT, CLOSES_AT);

        SaleEvent restored = SaleEvent.restore(new SaleEventSnapshot(
                origin.id(), origin.name(), origin.opensAt(), origin.closesAt(), origin.status()));

        assertThat(restored.id()).isEqualTo(origin.id());
        assertThat(restored.name()).isEqualTo(origin.name());
        assertThat(restored.opensAt()).isEqualTo(origin.opensAt());
        assertThat(restored.closesAt()).isEqualTo(origin.closesAt());
        assertThat(restored.status()).isEqualTo(origin.status());
    }
}
