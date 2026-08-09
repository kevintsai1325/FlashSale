package com.flashsale.identity.application;

import com.flashsale.common.exception.UnauthorizedException;
import com.flashsale.identity.domain.RefreshToken;
import com.flashsale.identity.domain.Role;
import com.flashsale.identity.domain.User;
import com.flashsale.identity.adapter.security.JwtIssuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock UserRepository userRepository;
    @Mock JwtIssuer jwtIssuer;

    RefreshTokenService service;

    @Test
    void validRefreshTokenIssuesNewAccessToken() {
        service = new RefreshTokenService(refreshTokenRepository, userRepository, jwtIssuer);
        String raw = "raw-token";
        String hash = LoginService.hash(raw);
        RefreshToken stored = RefreshToken.issue(1L, hash, Instant.now().plus(1, ChronoUnit.DAYS));
        User user = User.register("eve@example.com", "hashed", Role.USER);

        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(stored));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(jwtIssuer.issueAccessToken(user)).thenReturn("new-access-token");

        String result = service.refresh(raw);

        assertThat(result).isEqualTo("new-access-token");
    }

    @Test
    void expiredOrRevokedTokenThrowsUnauthorized() {
        service = new RefreshTokenService(refreshTokenRepository, userRepository, jwtIssuer);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("unknown-token"))
            .isInstanceOf(UnauthorizedException.class);
    }
}
