package com.flashsale.identity.adapter.web.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank(message = "電子郵件為必填") String email,
    @NotBlank(message = "密碼為必填") String password
) {}
