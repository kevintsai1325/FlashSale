package com.flashsale.identity.application;

import com.flashsale.identity.domain.RefreshToken;
import org.springframework.stereotype.Service;

@Service
public class LogoutService {

    private final RefreshTokenRepository refreshTokenRepository;

    public LogoutService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    public void logout(String rawToken) {
        String hash = LoginService.hash(rawToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
            token.revoke();
            refreshTokenRepository.save(token);
        });
    }
}
