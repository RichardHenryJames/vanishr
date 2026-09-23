package app.vanishr.relay;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http, AuthService auth, RateLimiter limiter) throws Exception {
        http.csrf(config -> config.disable()).cors(config -> config.disable())
                .formLogin(config -> config.disable()).httpBasic(config -> config.disable()).logout(config -> config.disable())
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(config -> config.disable())
                .headers(headers -> headers.contentSecurityPolicy(policy -> policy.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .referrerPolicy(policy -> policy.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/login", "/auth/google", "/auth/google/challenge", "/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/devices").hasAuthority("ENROLL")
                        .requestMatchers("/auth/me", "/auth/logout").authenticated()
                        .anyRequest().hasAuthority("DEVICE"))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> error(response, 401, "authentication_required"))
                        .accessDeniedHandler((request, response, failure) -> error(response, 403, "forbidden")))
                .addFilterBefore(new BoundaryFilter(auth, limiter), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }

    static final class BoundaryFilter extends OncePerRequestFilter {
        private final AuthService auth;
        private final RateLimiter limiter;
        BoundaryFilter(AuthService auth, RateLimiter limiter) { this.auth = auth; this.limiter = limiter; }

        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            if (!request.isSecure()) { error(response, 426, "tls_required"); return; }
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
                int limit = request.getRequestURI().startsWith("/media/") ? RelayPolicy.MAX_MEDIA_BYTES
                    : request.getRequestURI().matches("/groups/[0-9a-fA-F-]{36}/messages") ? 3_000_000 : 196_608;
            if (request.getContentLengthLong() > limit) { error(response, 413, "request_too_large"); return; }
            try {
                String source = RedisRelay.digest(request.getRemoteAddr().getBytes(StandardCharsets.US_ASCII));
                limiter.require("ip:" + source, 300, 60);
                String path = request.getRequestURI();
                if (path.equals("/auth/google/challenge")) limiter.require("google-challenge:" + source, 10, 60);
                else if (path.equals("/auth/refresh")) limiter.require("renew:" + source, 30, 60);
                else if (List.of("/auth/register", "/auth/login", "/auth/google").contains(path)) limiter.require("auth:" + source, 10, 60);
                String header = request.getHeader("Authorization");
                if (header != null) {
                    if (!header.startsWith("Bearer ") || header.length() != 50) { error(response, 401, "authentication_required"); return; }
                    String token = header.substring(7);
                    RelayTypes.Actor actor = auth.authenticate(token);
                    request.setAttribute("sessionKey", AuthService.tokenKey(token));
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null,
                            List.of(new SimpleGrantedAuthority(actor.deviceId() == null ? "ENROLL" : "DEVICE"))));
                    limiter.require("device:" + (actor.deviceId() == null ? actor.userId() : actor.deviceId()), 180, 60);
                }
                chain.doFilter(new LimitedRequest(request, limit), response);
            } catch (ApiException failure) { error(response, failure.status.value(), failure.getMessage()); }
            catch (org.springframework.dao.DataAccessException failure) { error(response, 503, "service_unavailable"); }
            finally { SecurityContextHolder.clearContext(); }
        }
    }

    static final class LimitedRequest extends HttpServletRequestWrapper {
        private final int limit;
        LimitedRequest(HttpServletRequest request, int limit) { super(request); this.limit = limit; }
        @Override public ServletInputStream getInputStream() throws IOException {
            ServletInputStream input = super.getInputStream();
            return new ServletInputStream() {
                private int count;
                @Override public boolean isFinished() { return input.isFinished(); }
                @Override public boolean isReady() { return input.isReady(); }
                @Override public void setReadListener(ReadListener listener) { input.setReadListener(listener); }
                @Override public int read() throws IOException {
                    int value = input.read();
                    if (value != -1 && ++count > limit) throw new IOException("Request limit exceeded");
                    return value;
                }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    int read = input.read(buffer, offset, Math.min(length, limit - count + 1));
                    if (read > 0 && (count += read) > limit) throw new IOException("Request limit exceeded");
                    return read;
                }
            };
        }
    }
}