package com.railgate.reservation.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrainScheduleId - 열차 운행편 식별자")
class TrainScheduleIdTest {

    @Test
    void 양수_식별자를_만들_수_있다() {
        assertThat(new TrainScheduleId(101L).value()).isEqualTo(101L);
    }

    @Test
    void 영은_거부한다() {
        assertThatThrownBy(() -> new TrainScheduleId(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 음수는_거부한다() {
        assertThatThrownBy(() -> new TrainScheduleId(-5L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 같은_값은_동등하다() {
        assertThat(new TrainScheduleId(101L)).isEqualTo(new TrainScheduleId(101L));
        assertThat(new TrainScheduleId(101L)).isNotEqualTo(new TrainScheduleId(102L));
    }
}
