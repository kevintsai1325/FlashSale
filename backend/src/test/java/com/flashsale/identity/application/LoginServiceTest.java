package com.flashsale.identity.application;

import com.flashsale.common.exception.UnauthorizedException;
import com.flashsale.identity.domain.Role;
import com.flashsale.identity.domain.User;
import com.flashsale.identity.adapter.security.JwtIssuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtIssuer jwtIssuer;
    @Mock RefreshTokenRepository refreshTokenRepository;

    LoginService service;

    @Test
    void loginWithValidCredentialsReturnsTokens() {
        service = new LoginService(userRepository, passwordEncoder, jwtIssuer, refreshTokenRepository);
        User user = User.register("dave@example.com", "hashed", Role.USER);
        when(userRepository.findByEmail("dave@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "hashed")).thenReturn(true);
        when(jwtIssuer.issueAccessToken(user)).thenReturn("access-token");
        when(jwtIssuer.issueRawRefreshToken()).thenReturn("raw-refresh-token");

        LoginService.LoginResult result = service.login("dave@example.com", "secret123");

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.rawRefreshToken()).isEqualTo("raw-refresh-token");
        verify(refreshTokenRepository).save(any());
    }

    @Test
    void loginWithWrongPasswordThrowsUnauthorized() {
        service = new LoginService(userRepository, passwordEncoder, jwtIssuer, refreshTokenRepository);
        User user = User.register("dave@example.com", "hashed", Role.USER);
        when(userRepository.findByEmail("dave@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> service.login("dave@example.com", "wrong"))
            .isInstanceOf(UnauthorizedException.class);
    }
}
