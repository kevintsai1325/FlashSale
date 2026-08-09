package com.flashsale.identity.application;

/**
 * Published after a new user is persisted, so downstream side effects (e.g. sending the
 * registration-success email) can react once the registration transaction has committed
 * and the user row is guaranteed visible to other connections/threads.
 */
public record UserRegisteredEvent(Long userId, String email) {
}
