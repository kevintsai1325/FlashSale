package com.flashsale.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Temporary stopgap so Task 7's public endpoints (e.g. {@code /api/auth/register}) work
 * before Task 8 adds the real JWT-based {@code SecurityConfig}.
 *
 * <p>{@code spring-boot-starter-security} is already on the classpath (added in the Task 2
 * backend skeleton), so Spring Boot's default security auto-configuration otherwise requires
 * authentication (and enforces CSRF) for every request, returning 401/403 on all endpoints
 * including public ones.
 *
 * <p><b>Task 8 must delete this class</b> when it introduces its own {@code SecurityFilterChain}
 * bean (in its {@code SecurityConfig}) — having two {@code SecurityFilterChain} beans without
 * explicit {@code @Order}/request-matcher partitioning will misbehave. Task 8's real config
 * should permit {@code /api/auth/register} and {@code /api/auth/login} (and any other
 * intentionally public paths) while requiring JWT auth elsewhere.
 */
@Configuration
public class PermitAllSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
