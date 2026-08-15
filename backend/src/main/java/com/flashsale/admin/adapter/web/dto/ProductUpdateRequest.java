package com.flashsale.admin.adapter.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ProductUpdateRequest(@NotBlank(message = "商品名稱為必填") String name, String description) {}
