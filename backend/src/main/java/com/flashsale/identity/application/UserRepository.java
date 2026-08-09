package com.flashsale.identity.application;

import com.flashsale.identity.domain.User;
import java.util.Optional;

public interface UserRepository {
    Optional<User> findByEmail(String email);
    User save(User user);
}
