package com.railgate.reservation.saleevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SaleEventId - 판매 회차 식별자")
class SaleEventIdTest {

    @Test
    void 양수_식별자를_만들_수_있다() {
        assertThat(new SaleEventId(9001L).value()).isEqualTo(9001L);
    }

    @Test
    void 영은_거부한다() {
        assertThatThrownBy(() -> new SaleEventId(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 음수는_거부한다() {
        assertThatThrownBy(() -> new SaleEventId(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 같은_값은_동등하다() {
        assertThat(new SaleEventId(9001L)).isEqualTo(new SaleEventId(9001L));
        assertThat(new SaleEventId(9001L)).isNotEqualTo(new SaleEventId(9002L));
    }

    /**
     * quota 잠금 순서가 {@code (saleEventId, userId)} 오름차순이라(2G-D §6)
     * 이 식별자는 정렬 가능해야 한다. {@code SeatId} 가 같은 이유로 Comparable 이다.
     */
    @Test
    void 잠금_순서를_위해_정렬할_수_있다() {
        assertThat(new SaleEventId(1L)).isLessThan(new SaleEventId(2L));
    }
}
