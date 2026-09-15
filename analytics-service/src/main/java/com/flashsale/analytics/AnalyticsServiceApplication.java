package com.flashsale.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * P5：把後台儀表板的數字從「跨三個服務各查一次」變成「查一份從事件算出來的讀取模型」。
 *
 * 拆庫之後，「搶購請求數」與「訂單數」分別住在兩個資料庫，一句 SQL 對不起來，
 * 而分別查兩次得到的是兩個時點的數字。這個服務訂閱同一條事件流，
 * 兩個數字從此出自同一個來源 —— 這是 CQRS 讀取模型在微服務裡最實際的用途。
 */
@SpringBootApplication
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
