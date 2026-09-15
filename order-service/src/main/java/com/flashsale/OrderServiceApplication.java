package com.flashsale;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * P5：訂單與庫存的服務。
 *
 * 為什麼庫存跟著訂單走而不是留在 platform：**不超賣的保證是「鎖住庫存列」與「建立訂單」
 * 在同一個本地交易裡**。把它們拆到兩個資料庫，那個保證就要換成分散式交易或補償，
 * 而補償無法防止超賣（只能事後修正）。這是整個 P5 裡唯一不能妥協的約束，
 * 其他邊界都是照著它決定的。
 *
 * 類別放在 com.flashsale 根目錄而不是 com.flashsale.order：@SpringBootApplication 的
 * 元件掃描以自己的套件為起點，放在子套件裡會掃不到 inventory 與 payment。
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
