package com.flashsale.purchase.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 排程（目前只有 OutboxPublisher）預設開啟，整合測試關掉。
 *
 * 測試必須自己決定「發佈」什麼時候發生：每 500 毫秒跑一次的背景排程會在測試斷言之前
 * 就把事件送出去，讓「這一步做了什麼」變成看運氣。與 backend 的 SchedulingConfig 同一套做法。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
