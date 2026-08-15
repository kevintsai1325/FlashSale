package com.flashsale.admin.adapter.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Body of {@code PATCH /api/admin/notifications/read-status}: {@code { ids: number[], read: boolean } }. */
public record ReadStatusRequest(
    @NotEmpty(message = "通知 ID 清單不可為空") List<@NotNull(message = "通知 ID 不可為空") Long> ids,
    @NotNull(message = "已讀狀態為必填") Boolean read
) {}
