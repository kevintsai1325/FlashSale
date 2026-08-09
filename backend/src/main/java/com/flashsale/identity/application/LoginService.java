package com.flashsale.identity.application;

import com.flashsale.common.exception.UnauthorizedException;
import com.flashsale.identity.adapter.security.JwtIssuer;
import com.flashsale.identity.domain.RefreshToken;
import com.flashsale.identity.domain.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Service
public class LoginService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtIssuer jwtIssuer;
    private final RefreshTokenRepository refreshTokenRepository;

    public LoginService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                         JwtIssuer jwtIssuer, RefreshTokenRepository refreshTokenRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtIssuer = jwtIssuer;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    public record LoginResult(String accessToken, String rawRefreshToken) {}

    @Transactional
    public LoginResult login(String email, String rawPassword) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new UnauthorizedException("INVALID_CREDENTIALS", "Invalid email or password"));

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new UnauthorizedException("INVALID_CREDENTIALS", "Invalid email or password");
        }

        String accessToken = jwtIssuer.issueAccessToken(user);
        String rawRefreshToken = jwtIssuer.issueRawRefreshToken();
        String hash = hash(rawRefreshToken);

        refreshTokenRepository.save(
            RefreshToken.issue(user.getId(), hash, Instant.now().plus(30, ChronoUnit.DAYS)));

        return new LoginResult(accessToken, rawRefreshToken);
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
