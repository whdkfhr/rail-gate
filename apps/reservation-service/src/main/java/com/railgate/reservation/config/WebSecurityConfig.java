package com.railgate.reservation.config;

import com.railgate.reservation.UserId;
import com.railgate.queuetoken.AdmissionTokenVerifier;
import com.railgate.reservation.api.security.AccessTokenAuthentication;
import com.railgate.reservation.api.security.AdmissionTokenFilter;
import com.railgate.reservation.api.security.AuthenticatedUser;
import com.railgate.reservation.api.security.JsonAccessDeniedHandler;
import com.railgate.reservation.api.security.JsonAuthenticationEntryPoint;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * 보안 필터 체인 (ADR-015 §3, ADR-016). <b>기본 거부</b>다.
 *
 * <table>
 *   <tr><th>경로</th><th>규칙</th></tr>
 *   <tr><td>{@code GET /actuator/health}</td><td>공개 (상세 비노출 — {@code show-details: never})</td></tr>
 *   <tr><td>{@code /api/**}</td><td>유효한 액세스 토큰 필요</td></tr>
 *   <tr><td>그 밖의 모든 경로 ({@code /actuator/metrics}·{@code info} 포함)</td><td><b>거부</b> — 토큰 없으면 401, 있으면 403</td></tr>
 * </table>
 *
 * <p>상태를 두지 않는다(세션·CSRF·폼 로그인·HTTP Basic 없음). Bearer 토큰은 {@code Authorization} 헤더에서만 받는다 —
 * 쿼리 문자열·폼 파라미터의 토큰은 무시한다. 오류 디스패치는 원래 요청에서 이미 판정됐으므로 허용한다.
 *
 * <p><b>입장 토큰</b>({@code POST /api/reservations/holds}) — {@link AdmissionTokenFilter} 가 인가 필터 뒤에서 검증하고
 * 두 토큰의 사용자를 대조한다 (Task 2J-C).
 *
 * <p><b>아직 없는 것</b> — 입장 회차와 요청 좌석의 회차 대조(Task 2J-D), 선점 컨트롤러, 발급 엔드포인트.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class WebSecurityConfig {

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http, JwtDecoder accessTokenDecoder, AdmissionTokenVerifier admissionTokenVerifier,
            MeterRegistry meters) throws Exception {
        JsonAuthenticationEntryPoint entryPoint = new JsonAuthenticationEntryPoint();
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(new JsonAccessDeniedHandler())
                        .jwt(jwt -> jwt
                                .decoder(accessTokenDecoder)
                                .jwtAuthenticationConverter(token -> new AccessTokenAuthentication(
                                        new AuthenticatedUser(new UserId(Long.parseLong(token.getSubject())))))))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(new JsonAccessDeniedHandler()))
                // 입장 검증은 인가(로그인 확인) 뒤다 — 액세스 오류가 입장 오류보다 먼저다. 빈이 아니라 체인에만 넣는다.
                .addFilterAfter(new AdmissionTokenFilter(admissionTokenVerifier, meters), AuthorizationFilter.class);
        return http.build();
    }
}
