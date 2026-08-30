package com.railgate.reservation.saleevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 판매 회차 상태 전이표 전수 검증.
 *
 * <p>3개 상태 × 2개 행위 = 6개 조합을 빠짐없이 검증한다.
 * {@code SeatTransitionTableTest} 와 같은 방식이다 — 새 상태나 행위를 추가하면
 * 커버리지 테스트가 먼저 깨지므로 전이 정의를 빠뜨릴 수 없다.
 *
 * <pre>
 *            open                     close
 * SCHEDULED  → OPEN (now >= opensAt)  ✗ 전이 위반
 * OPEN       ✗ 전이 위반               → CLOSED
 * CLOSED     ✗ 전이 위반               ✗ 전이 위반   ★ 터미널
 * </pre>
 *
 * <p>허용 2개, 거부 4개다. {@code SCHEDULED → CLOSED} 를 거부하는 이유는 <b>열린 적 없는
 * 회차를 닫는다는 것이 업무적으로 무엇인지 정해지지 않았기 때문이다.</b> 오픈 전 취소는
 * 별도의 요구사항과 상태 모델 결정을 거쳐야 한다 (TASK-002G-E-A §7 미결정 항목).
 */
@DisplayName("SaleEvent 전이표 - 정의된 전이만 허용한다")
class SaleEventTransitionTableTest {

    private static final SaleEventId ID = new SaleEventId(9001L);
    private static final String NAME = "2026년 추석 승차권 1차 판매";
    private static final Instant OPENS_AT = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant CLOSES_AT = Instant.parse("2026-08-10T00:00:00Z");
    private static final Instant AFTER_OPEN = OPENS_AT.plusSeconds(1);

    /** 판매 회차에 가할 수 있는 모든 도메인 행위. */
    private enum SaleEventAction {
        // 오픈 시각 판정은 항상 파라미터로 받는다 (CLAUDE.md 규칙 7).
        OPEN(event -> event.open(AFTER_OPEN)),
        CLOSE(SaleEvent::close);

        private final Consumer<SaleEvent> action;

        SaleEventAction(Consumer<SaleEvent> action) {
            this.action = action;
        }

        void applyTo(SaleEvent event) {
            action.accept(event);
        }
    }

    /**
     * 요청한 상태의 회차를 만든다.
     *
     * <p><b>복원({@code restore})이 아니라 실제 전이로 만든다.</b> 복원으로 만들면
     * 전이표가 "복원이 허용하는 조합" 을 검증하게 되어 자기참조가 된다.
     * 여기서 검증할 것은 도메인이 실제로 만들어내는 상태다.
     */
    private static SaleEvent saleEventIn(SaleEventStatus status) {
        SaleEvent event = SaleEvent.schedule(ID, NAME, OPENS_AT, CLOSES_AT);
        if (status == SaleEventStatus.SCHEDULED) {
            return event;
        }
        event.open(AFTER_OPEN);
        if (status == SaleEventStatus.OPEN) {
            return event;
        }
        event.close();
        return event;
    }

    static Stream<Arguments> 허용되는_전이() {
        return Stream.of(
                Arguments.of(SaleEventStatus.SCHEDULED, SaleEventAction.OPEN, SaleEventStatus.OPEN),
                Arguments.of(SaleEventStatus.OPEN, SaleEventAction.CLOSE, SaleEventStatus.CLOSED));
    }

    static Stream<Arguments> 금지되는_전이() {
        return Stream.of(
                // 열린 적 없는 회차를 닫는다는 것의 업무적 의미가 정해지지 않았다.
                Arguments.of(SaleEventStatus.SCHEDULED, SaleEventAction.CLOSE),

                // 이미 열린 회차를 다시 여는 것은 전이가 아니다.
                Arguments.of(SaleEventStatus.OPEN, SaleEventAction.OPEN),

                // ★ CLOSED 는 터미널이다. 나가는 전이가 없다.
                Arguments.of(SaleEventStatus.CLOSED, SaleEventAction.OPEN),
                Arguments.of(SaleEventStatus.CLOSED, SaleEventAction.CLOSE));
    }

    @ParameterizedTest(name = "{0} 상태에서 {1} 하면 {2} 가 된다")
    @MethodSource("허용되는_전이")
    void 정의된_전이는_허용된다(SaleEventStatus from, SaleEventAction action, SaleEventStatus expected) {
        SaleEvent event = saleEventIn(from);

        action.applyTo(event);

        assertThat(event.status()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} 상태에서는 {1} 할 수 없다")
    @MethodSource("금지되는_전이")
    void 정의되지_않은_전이는_거부된다(SaleEventStatus from, SaleEventAction action) {
        SaleEvent event = saleEventIn(from);

        assertThatThrownBy(() -> action.applyTo(event))
                .isInstanceOf(SaleEventStateTransitionException.class);
    }

    @ParameterizedTest(name = "{0} 상태에서 거부된 {1} 는 상태를 바꾸지 않는다")
    @MethodSource("금지되는_전이")
    void 거부된_전이는_상태를_바꾸지_않는다(SaleEventStatus from, SaleEventAction action) {
        SaleEvent event = saleEventIn(from);

        assertThatThrownBy(() -> action.applyTo(event))
                .isInstanceOf(SaleEventStateTransitionException.class);

        assertThat(event.status()).isEqualTo(from);
    }

    /**
     * 커버리지 검증.
     *
     * <p><b>개수 합계만 맞추지 않는다.</b> 같은 조합을 두 번 적고 다른 조합을 빠뜨려도
     * 합계는 맞는다. 그래서 <b>중복 없이 전부</b> 있는지를 조합 자체로 확인한다.
     */
    @Nested
    @DisplayName("전이표 커버리지")
    class 전이표_커버리지 {

        private List<List<Object>> 모든_표_항목() {
            return Stream.concat(
                            허용되는_전이().map(args -> List.of(args.get()[0], args.get()[1])),
                            금지되는_전이().map(args -> List.of(args.get()[0], args.get()[1])))
                    .toList();
        }

        @Test
        void 같은_조합이_두_번_등장하지_않는다() {
            List<List<Object>> 항목 = 모든_표_항목();

            assertThat(항목).doesNotHaveDuplicates();
        }

        @Test
        void 모든_상태와_행위_조합이_빠짐없이_있다() {
            List<List<Object>> 기대 = Stream.of(SaleEventStatus.values())
                    .flatMap(status -> Stream.of(SaleEventAction.values())
                            .map(action -> List.<Object>of(status, action)))
                    .toList();

            assertThat(모든_표_항목())
                    .as("상태 %d개 × 행위 %d개 조합이 모두 표에 있어야 한다",
                            SaleEventStatus.values().length, SaleEventAction.values().length)
                    .containsExactlyInAnyOrderElementsOf(기대);
        }

        @Test
        void 허용_2개_거부_4개다() {
            assertThat(허용되는_전이().count()).isEqualTo(2);
            assertThat(금지되는_전이().count()).isEqualTo(4);
        }
    }

    /**
     * 오픈 시각 경계.
     *
     * <p>회차를 "하나의 오픈 시각으로 운영하는 단위" 로 정의한 TASK-002G-D §4 계약의
     * 도메인 표현이다. 경계는 DB 의 {@code opens_at <= NOW(3)} 과 같게 맞춘다.
     */
    @Nested
    @DisplayName("오픈 시각 경계")
    class 오픈_시각_경계 {

        @Test
        void 오픈_시각_이전에는_열_수_없다() {
            SaleEvent event = SaleEvent.schedule(ID, NAME, OPENS_AT, CLOSES_AT);

            assertThatThrownBy(() -> event.open(OPENS_AT.minusSeconds(1)))
                    .isInstanceOf(SaleEventStateTransitionException.class);
        }

        @Test
        void 오픈_시각_이전의_실패는_상태를_바꾸지_않는다() {
            SaleEvent event = SaleEvent.schedule(ID, NAME, OPENS_AT, CLOSES_AT);

            assertThatThrownBy(() -> event.open(OPENS_AT.minusSeconds(1)))
                    .isInstanceOf(SaleEventStateTransitionException.class);

            assertThat(event.status()).isEqualTo(SaleEventStatus.SCHEDULED);
            assertThat(event.isOpen()).isFalse();
        }

        @Test
        void 오픈_시각과_같은_순간에는_열_수_있다() {
            SaleEvent event = SaleEvent.schedule(ID, NAME, OPENS_AT, CLOSES_AT);

            event.open(OPENS_AT);

            assertThat(event.status()).isEqualTo(SaleEventStatus.OPEN);
            assertThat(event.isOpen()).isTrue();
        }

        @Test
        void 판정_시각이_없으면_거부한다() {
            SaleEvent event = SaleEvent.schedule(ID, NAME, OPENS_AT, CLOSES_AT);

            assertThatThrownBy(() -> event.open(null))
                    .isInstanceOf(NullPointerException.class);
            assertThat(event.status()).isEqualTo(SaleEventStatus.SCHEDULED);
        }
    }

    @Nested
    @DisplayName("마감")
    class 마감 {

        /**
         * 열린 적 없는 회차는 닫을 수 없다.
         *
         * <p>오픈 전 취소가 필요하다는 이유로 이 전이를 열지 않는다. 그것은 별도의
         * 요구사항과 상태 모델 결정을 거쳐야 하는 후속 사항이다.
         */
        @Test
        void SCHEDULED_에서는_마감할_수_없다() {
            SaleEvent event = SaleEvent.schedule(ID, NAME, OPENS_AT, CLOSES_AT);

            assertThatThrownBy(event::close)
                    .isInstanceOf(SaleEventStateTransitionException.class);
        }

        @Test
        void SCHEDULED_에서_거부된_마감은_상태를_바꾸지_않는다() {
            SaleEvent event = SaleEvent.schedule(ID, NAME, OPENS_AT, CLOSES_AT);

            assertThatThrownBy(event::close)
                    .isInstanceOf(SaleEventStateTransitionException.class);

            assertThat(event.status()).isEqualTo(SaleEventStatus.SCHEDULED);
        }

        /**
         * {@code closesAt} 은 <b>예정</b> 시각일 뿐이다. 매진이나 운영 중단으로 인한 조기
         * 마감은 정상적인 운영 행위이므로, {@code now >= closesAt} 을 요구하면 그 경로가 막힌다.
         */
        @Test
        void 마감_예정_시각_전에도_조기_마감할_수_있다() {
            SaleEvent event = saleEventIn(SaleEventStatus.OPEN);

            event.close();

            assertThat(event.status()).isEqualTo(SaleEventStatus.CLOSED);
            assertThat(event.isOpen()).isFalse();
        }

        @Test
        void 마감_시각이_없는_회차도_마감할_수_있다() {
            SaleEvent event = SaleEvent.schedule(ID, NAME, OPENS_AT, null);
            event.open(AFTER_OPEN);

            event.close();

            assertThat(event.status()).isEqualTo(SaleEventStatus.CLOSED);
        }
    }

    /** CLOSED 는 터미널이다. 어떤 행위로도 나갈 수 없다. */
    @Test
    void CLOSED_에서는_열지도_닫지도_못한다() {
        SaleEvent event = saleEventIn(SaleEventStatus.CLOSED);

        assertThatThrownBy(() -> event.open(AFTER_OPEN))
                .isInstanceOf(SaleEventStateTransitionException.class);
        assertThatThrownBy(event::close)
                .isInstanceOf(SaleEventStateTransitionException.class);

        assertThat(event.status()).isEqualTo(SaleEventStatus.CLOSED);
    }

    /** 전이는 식별자와 판매 기간을 건드리지 않는다. 바뀌는 것은 상태뿐이다. */
    @Test
    void 전이는_상태_외의_값을_바꾸지_않는다() {
        SaleEvent event = SaleEvent.schedule(ID, NAME, OPENS_AT, CLOSES_AT);

        event.open(AFTER_OPEN);
        event.close();

        assertThat(event.id()).isEqualTo(ID);
        assertThat(event.name()).isEqualTo(NAME);
        assertThat(event.opensAt()).isEqualTo(OPENS_AT);
        assertThat(event.closesAt()).isEqualTo(CLOSES_AT);
    }
}
