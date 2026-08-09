package com.flashsale.identity.application;

import com.flashsale.identity.domain.Role;
import com.flashsale.identity.domain.User;
import com.flashsale.notification.application.NotificationSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegisterUserServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock NotificationSender notificationSender;

    RegisterUserService service;

    @Test
    void registersNewUserAndSendsNotification() {
        service = new RegisterUserService(userRepository, passwordEncoder, notificationSender);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret123")).thenReturn("hashed");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.register("alice@example.com", "secret123");

        assertThat(result.getEmail()).isEqualTo("alice@example.com");
        assertThat(result.getPasswordHash()).isEqualTo("hashed");
        assertThat(result.getRole()).isEqualTo(Role.USER);
        verify(notificationSender).send(any());
    }

    @Test
    void rejectsDuplicateEmail() {
        service = new RegisterUserService(userRepository, passwordEncoder, notificationSender);
        when(userRepository.findByEmail("alice@example.com"))
            .thenReturn(Optional.of(User.register("alice@example.com", "x", Role.USER)));

        assertThatThrownBy(() -> service.register("alice@example.com", "secret123"))
            .isInstanceOf(com.flashsale.common.exception.ConflictException.class);
    }
}
