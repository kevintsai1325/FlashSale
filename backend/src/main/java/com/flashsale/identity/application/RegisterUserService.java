package com.flashsale.identity.application;

import com.flashsale.common.exception.ConflictException;
import com.flashsale.identity.domain.Role;
import com.flashsale.identity.domain.User;
import com.flashsale.notification.application.NotificationSender;
import com.flashsale.notification.domain.NotificationDelivery;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegisterUserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationSender notificationSender;

    public RegisterUserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                NotificationSender notificationSender) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationSender = notificationSender;
    }

    @Transactional
    public User register(String email, String rawPassword) {
        userRepository.findByEmail(email).ifPresent(existing -> {
            throw new ConflictException("EMAIL_ALREADY_REGISTERED", "Email is already registered");
        });

        User user = User.register(email, passwordEncoder.encode(rawPassword), Role.USER);
        User saved = userRepository.save(user);

        notificationSender.send(
            NotificationDelivery.pendingEmail(saved.getId(), "registration-success", saved.getEmail()));

        return saved;
    }
}
