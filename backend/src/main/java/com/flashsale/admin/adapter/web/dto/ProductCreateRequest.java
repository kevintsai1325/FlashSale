package com.flashsale.admin.adapter.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ProductCreateRequest(@NotBlank String name, String description) {}
