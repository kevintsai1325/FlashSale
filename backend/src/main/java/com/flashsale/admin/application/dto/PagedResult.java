package com.flashsale.admin.application.dto;

import java.util.List;

/**
 * Minimal, explicit paging envelope for admin list endpoints. Deliberately not Spring Data's
 * {@code Page<T>} — returning that type directly from a controller ties the JSON response shape
 * to whichever Jackson module happens to be on the classpath; this project has no other paged
 * endpoint yet to establish that convention, so a small local record keeps the response shape
 * explicit and stable.
 */
public record PagedResult<T>(List<T> content, long totalElements, int page, int size) {}
