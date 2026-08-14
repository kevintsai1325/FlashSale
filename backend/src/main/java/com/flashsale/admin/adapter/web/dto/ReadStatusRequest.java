package com.flashsale.admin.adapter.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Body of {@code PATCH /api/admin/notifications/read-status}: {@code { ids: number[], read: boolean } }. */
public record ReadStatusRequest(
    @NotEmpty List<@NotNull Long> ids,
    @NotNull Boolean read
) {}
