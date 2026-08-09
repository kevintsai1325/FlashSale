package com.flashsale.identity.application;

import com.flashsale.common.exception.ConflictException;
import com.flashsale.identity.domain.Role;
import com.flashsale.identity.domain.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegisterUserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public RegisterUserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public User register(String email, String rawPassword) {
        userRepository.findByEmail(email).ifPresent(existing -> {
            throw new ConflictException("EMAIL_ALREADY_REGISTERED", "Email is already registered");
        });

        User user = User.register(email, passwordEncoder.encode(rawPassword), Role.USER);
        User saved = userRepository.save(user);

        // Published now, but only delivered to @TransactionalEventListener(AFTER_COMMIT)
        // listeners once this transaction commits - see UserRegisteredNotificationListener.
        // This avoids the notification side effect racing the parent transaction's commit
        // (the async email send would otherwise run on a separate connection before the
        // new user row is visible, causing an FK violation on notification_deliveries).
        eventPublisher.publishEvent(new UserRegisteredEvent(saved.getId(), saved.getEmail()));

        return saved;
    }
}
