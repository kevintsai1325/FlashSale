package com.flashsale.identity.adapter.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "電子郵件為必填") @Email(message = "電子郵件格式不正確") String email,
    @NotBlank(message = "密碼為必填") @Size(min = 8, max = 100, message = "密碼長度需介於 8 到 100 字元之間") String password
) {}
