package com.flashsale.identity.adapter.persistence;

import com.flashsale.identity.application.RefreshTokenRepository;
import com.flashsale.identity.domain.RefreshToken;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class RefreshTokenRepositoryImpl implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository jpaRepository;

    public RefreshTokenRepositoryImpl(RefreshTokenJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public RefreshToken save(RefreshToken token) { return jpaRepository.save(token); }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash);
    }
}
