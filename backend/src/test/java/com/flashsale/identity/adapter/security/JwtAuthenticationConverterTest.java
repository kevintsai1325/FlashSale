package com.flashsale.identity.adapter.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces finding #1 from the Week 1 review: JwtIssuer emits the user's role as a plain
 * "role" claim (e.g. "ADMIN"), but Spring Security's default JwtAuthenticationConverter only
 * looks at "scope"/"scp" claims — so hasRole("ADMIN") in SecurityConfig could never be
 * satisfied by any real token, no matter the user's actual role.
 */
class JwtAuthenticationConverterTest {

    private final SecurityConfig securityConfig = new SecurityConfig(null, null);

    private Jwt jwtWithRole(String role) {
        return Jwt.withTokenValue("test-token")
            .header("alg", "RS256")
            .claim("sub", "someone@example.com")
            .claim("role", role)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();
    }

    @Test
    void mapsAdminRoleClaimToRoleAdminAuthority() {
        var converter = securityConfig.jwtAuthenticationConverter();

        var authentication = converter.convert(jwtWithRole("ADMIN"));

        assertThat(authentication.getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_ADMIN");
    }

    @Test
    void mapsUserRoleClaimToRoleUserAuthority() {
        var converter = securityConfig.jwtAuthenticationConverter();

        var authentication = converter.convert(jwtWithRole("USER"));

        assertThat(authentication.getAuthorities())
            .extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_USER");
    }
}
