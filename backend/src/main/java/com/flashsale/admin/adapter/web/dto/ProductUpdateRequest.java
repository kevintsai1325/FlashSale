package com.flashsale.admin.adapter.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ProductUpdateRequest(@NotBlank String name, String description) {}
