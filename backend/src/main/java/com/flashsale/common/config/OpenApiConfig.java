package com.flashsale.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI flashSaleOpenApi() {
        return new OpenAPI().info(new Info()
            .title("FlashSale API")
            .version("v1")
            .description("Limited-inventory flash sale demo API"));
    }
}
