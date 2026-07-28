package com.railgate.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 애플리케이션 컨텍스트가 정상적으로 뜨는지만 확인한다.
 * 빈 설정 오류를 가장 이른 시점에 잡기 위한 최소 안전망이다.
 */
@SpringBootTest
class ReservationServiceApplicationTest {

    @Test
    void contextLoads() {
    }
}
