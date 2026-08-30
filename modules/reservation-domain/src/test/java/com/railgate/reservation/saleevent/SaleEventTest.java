package com.railgate.reservation.saleevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SaleEvent 생성 불변식.
 *
 * <p>판매 회차는 "하나의 오픈 시각과 입장 정책으로 운영하는 예매 판매 회차" 다 (Task 2G-D §4).
 * I-12 quota 범위가 이 식별자 단위이므로, 잘못 만들어진 회차는 quota 의 의미도 흔든다.
 */
@DisplayName("SaleEvent - 생성 불변식")
class SaleEventTest {

    private static final SaleEventId ID = new SaleEventId(9001L);
    private static final String NAME = "2026년 추석 승차권 1차 판매";
    private static final Instant OPENS_AT = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant CLOSES_AT = Instant.parse("2026-08-10T00:00:00Z");

    @Nested
    @DisplayName("생성")
    class 생성 {

        @Test
        void 새_판매_회차는_SCHEDULED_로_시작한다() {
            SaleEvent event = SaleEvent.schedule(ID, NAME, OPENS_AT, CLOSES_AT);

            assertThat(event.id()).isEqualTo(ID);
            assertThat(event.name()).isEqualTo(NAME);
            assertThat(event.opensAt()).isEqualTo(OPENS_AT);
            assertThat(event.closesAt()).isEqualTo(CLOSES_AT);
            assertThat(event.status()).isEqualTo(SaleEventStatus.SCHEDULED);
        }

        @Test
        void 마감_시각은_없어도_된다() {
            SaleEvent event = SaleEvent.schedule(ID, NAME, OPENS_AT, null);

            assertThat(event.closesAt()).isNull();
            assertThat(event.status()).isEqualTo(SaleEventStatus.SCHEDULED);
        }

        @Test
        void 식별자가_없으면_거부한다() {
            assertThatThrownBy(() -> SaleEvent.schedule(null, NAME, OPENS_AT, CLOSES_AT))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void 이름이_없으면_거부한다() {
            assertThatThrownBy(() -> SaleEvent.schedule(ID, null, OPENS_AT, CLOSES_AT))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 이름이_공백뿐이면_거부한다() {
            assertThatThrownBy(() -> SaleEvent.schedule(ID, "   ", OPENS_AT, CLOSES_AT))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 오픈_시각이_없으면_거부한다() {
            assertThatThrownBy(() -> SaleEvent.schedule(ID, NAME, null, CLOSES_AT))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void 마감_시각이_오픈_시각과_같으면_거부한다() {
            assertThatThrownBy(() -> SaleEvent.schedule(ID, NAME, OPENS_AT, OPENS_AT))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 마감_시각이_오픈_시각보다_이르면_거부한다() {
            assertThatThrownBy(
                    () -> SaleEvent.schedule(ID, NAME, OPENS_AT, OPENS_AT.minusSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 올바른_판매_기간은_허용한다() {
            assertThatCode(() -> SaleEvent.schedule(ID, NAME, OPENS_AT, OPENS_AT.plusSeconds(1)))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * 도메인은 시각을 스스로 읽지 않는다.
     *
     * <p>{@code Instant.now()} 를 도메인 안에서 부르면 테스트가 실행 시각에 좌우되고,
     * 인스턴스 간 클럭 드리프트가 판정에 섞인다 (CLAUDE.md 규칙 7 과 같은 이유).
     * {@code Seat.expire(Instant now)} 가 시각을 파라미터로 받는 것과 같은 원칙이다.
     */
    @Nested
    @DisplayName("시각 취급")
    class 시각_취급 {

        @Test
        void 같은_입력이면_언제_실행해도_같은_결과다() {
            SaleEvent first = SaleEvent.schedule(ID, NAME, OPENS_AT, CLOSES_AT);
            SaleEvent second = SaleEvent.schedule(ID, NAME, OPENS_AT, CLOSES_AT);

            assertThat(first.opensAt()).isEqualTo(second.opensAt());
            assertThat(first.closesAt()).isEqualTo(second.closesAt());
            assertThat(first.status()).isEqualTo(second.status());
        }
    }
}
