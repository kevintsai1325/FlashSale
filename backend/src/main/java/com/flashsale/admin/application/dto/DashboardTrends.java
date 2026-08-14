package com.flashsale.admin.application.dto;

import java.util.List;

public record DashboardTrends(List<TrendPoint> lastHour, List<TrendPoint> last24Hours) {}
