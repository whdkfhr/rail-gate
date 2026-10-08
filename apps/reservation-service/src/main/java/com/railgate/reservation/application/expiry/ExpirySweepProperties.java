package com.railgate.reservation.application.expiry;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 만료 배치 스케줄러 설정 (`railgate.expiry.sweep.*`).
 *
 * <h2>★ {@code enabled} 가 기본 {@code false} 인 이유</h2>
 *
 * <p>만료 회수는 <b>좌석을 되돌리고 quota 를 줄이는 쓰기 작업</b>이다. 기존 데이터가 있는 배포에서
 * 카운터가 비어 있거나 어긋나 있으면 그룹이 실패로 남거나(의도된 동작) 운영자가 모르는 사이
 * drift 가 쌓인다. <b>backfill(TASK-002G-G §4)과 drift 점검(V-7a·b·c)을 마치기 전에는 켜면 안 된다.</b>
 *
 * <p>그래서 켜는 행위를 <b>명시적 설정</b>으로 만든다. 끄여 있으면 스케줄 등록 자체가 일어나지 않고,
 * 잘못된 값이 들어 있어도 기동을 막지 않는다 — 켜지 않은 설정은 검증 대상이 아니다.
 */
@ConfigurationProperties(prefix = "railgate.expiry.sweep")
public class ExpirySweepProperties {

    /** 기본 {@code false}. 명시적으로 켰을 때만 스케줄러가 등록·동작한다. */
    private boolean enabled = false;

    /**
     * 직전 실행이 <b>끝난 뒤</b> 다음 실행까지의 간격.
     *
     * <p>{@code fixedRate} 가 아닌 이유는 배치가 느려졌을 때 실행이 밀려 겹치는 것을 피하기
     * 위해서다. 겹침은 어댑터도 따로 막지만, 애초에 쌓이지 않게 두는 편이 낫다.
     */
    private Duration fixedDelay = Duration.ofSeconds(30);

    /** 후보 SELECT 한 번의 {@code LIMIT}. 벌크 UPDATE 크기의 상한이 아니다. */
    private int pageSize = 100;

    /** 한 tick 이 읽을 수 있는 최대 페이지 수. 검사 후보 수의 상한은 {@code pageSize × maxPages} 다. */
    private int maxPages = ExpirySweepRequest.DEFAULT_MAX_PAGES;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getFixedDelay() {
        return fixedDelay;
    }

    public void setFixedDelay(Duration fixedDelay) {
        this.fixedDelay = fixedDelay;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }
}
