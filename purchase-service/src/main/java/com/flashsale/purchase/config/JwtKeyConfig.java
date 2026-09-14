package com.flashsale.purchase.config;

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
    private String publicKeyBase64;

    @Bean
    public RSAPublicKey rsaPublicKey() throws Exception {
        byte[] decoded = Base64.getDecoder().decode(publicKeyBase64.replaceAll("\\s", ""));
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAPublicKey publicKey) {
        return NimbusJwtDecoder.withPublicKey(publicKey).build();
    }
}
