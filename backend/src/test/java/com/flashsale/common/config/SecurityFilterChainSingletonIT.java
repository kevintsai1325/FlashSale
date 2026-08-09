package com.flashsale.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against Task 8 landing its own {@link SecurityFilterChain} bean without deleting
 * {@link PermitAllSecurityConfig} first.
 *
 * <p>Spring Security does NOT fail fast when two unordered {@code SecurityFilterChain} beans
 * both default-match every request ({@code /**}) — they can silently coexist rather than
 * erroring at startup. That means if Task 8 adds a real JWT {@code SecurityFilterChain} but
 * forgets to delete {@link PermitAllSecurityConfig}, the app could boot with real auth AND a
 * live permit-all/CSRF-disabled chain running side by side, silently defeating auth.
 *
 * <p>This test turns "hopefully Task 8 remembers" into an enforced invariant: it passes today
 * because {@link PermitAllSecurityConfig} is the only source of a {@code SecurityFilterChain}
 * bean, and it will fail the moment a second one is registered anywhere in the app context.
 *
 * <p><b>If this test fails after Task 8 lands its real {@code SecurityConfig}:</b> delete
 * {@link PermitAllSecurityConfig} (see its own Javadoc) — do not try to make this test pass
 * any other way (e.g. by ordering the chains or narrowing their request matchers), since the
 * whole point of {@code PermitAllSecurityConfig} was to be a temporary stopgap for Task 7 only.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
class SecurityFilterChainSingletonIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    List<SecurityFilterChain> securityFilterChains;

    @Test
    void exactlyOneSecurityFilterChainBeanExists() {
        assertThat(securityFilterChains)
            .as("Expected exactly one SecurityFilterChain bean in the context. If Task 8 added "
                + "its own SecurityConfig, delete PermitAllSecurityConfig.java (see its Javadoc) "
                + "— two unordered SecurityFilterChain beans can silently coexist instead of "
                + "erroring, which would leave a permit-all/CSRF-disabled chain live alongside "
                + "real auth.")
            .hasSize(1);
    }
}
