package com.flashsale.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Provides the {@link PasswordEncoder} bean needed by Task 7's registration flow.
 *
 * <p>Task 8 owns {@code SecurityConfig} and is expected to define the app's real
 * {@code PasswordEncoder} bean there. When Task 8 lands, delete this class (or move its
 * {@code @Bean} method into {@code SecurityConfig}) rather than adding a second
 * {@code PasswordEncoder} bean — Spring will fail to start with a
 * {@code NoUniqueBeanDefinitionException} if both exist.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
