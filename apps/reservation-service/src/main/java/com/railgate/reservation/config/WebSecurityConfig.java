package com.railgate.reservation.config;

import com.railgate.reservation.UserId;
import com.railgate.reservation.api.security.AccessTokenAuthentication;
import com.railgate.reservation.api.security.AuthenticatedUser;
import com.railgate.reservation.api.security.JsonAccessDeniedHandler;
import com.railgate.reservation.api.security.JsonAuthenticationEntryPoint;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

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
 * <p><b>아직 없는 것</b> — 입장 토큰 검증(ADR-015 §4), 사용자 대조(§5), 선점 컨트롤러, 발급 엔드포인트.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class WebSecurityConfig {

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtDecoder accessTokenDecoder) throws Exception {
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
                        .accessDeniedHandler(new JsonAccessDeniedHandler()));
        return http.build();
    }
}
