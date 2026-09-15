package com.flashsale.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * purchase-service 只驗證 token，不簽發 token —— 所以這裡只要公鑰。
 * 私鑰留在 backend 的 identity 模組：purchase-service 不應該有能力簽出一個有效的 token。
 */
@Configuration
public class JwtKeyConfig {

    @Value("${JWT_PUBLIC_KEY}")
    private String publicKeyPem;

    @Bean
    public RSAPublicKey rsaPublicKey() throws Exception {
        // 金鑰是以 PEM 形式存進 Secret 的，必須先把 BEGIN/END 那兩行拿掉再 base64 解碼 ——
        // 只做 replaceAll("\\s", "") 會留下 "-----BEGIN PUBLIC KEY-----"，
        // 症狀是啟動時的 "Illegal base64 character 2d"（2d 就是那個減號）。
        String cleaned = publicKeyPem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(cleaned);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAPublicKey publicKey) {
        return NimbusJwtDecoder.withPublicKey(publicKey).build();
    }
}
