package com.flashsale.identity.application;

import com.flashsale.common.exception.UnauthorizedException;
import com.flashsale.identity.adapter.security.JwtIssuer;
import com.flashsale.identity.domain.RefreshToken;
import com.flashsale.identity.domain.User;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtIssuer jwtIssuer;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, UserRepository userRepository,
                                JwtIssuer jwtIssuer) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.jwtIssuer = jwtIssuer;
    }

    public String refresh(String rawToken) {
        String hash = LoginService.hash(rawToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash)
            .filter(t -> t.isValid(Instant.now()))
            .orElseThrow(() -> new UnauthorizedException("INVALID_REFRESH_TOKEN", "登入憑證已失效,請重新登入"));

        User user = userRepository.findById(token.getUserId())
            .orElseThrow(() -> new UnauthorizedException("INVALID_REFRESH_TOKEN", "使用者不存在"));

        return jwtIssuer.issueAccessToken(user);
    }
}
