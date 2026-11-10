package com.railgate.reservation.config;

import com.railgate.reservation.api.trace.TraceIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * traceId 필터 등록. <b>가장 먼저</b> 실행되도록 최고 우선순위로 둔다 — Spring Security 필터 체인(기본 -100)보다 앞이다.
 * 필터 인스턴스는 빈이 아니다 (중복 등록 방지).
 */
@Configuration(proxyBeanMethods = false)
public class TraceConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        registration.setName("traceIdFilter");
        return registration;
    }
}
