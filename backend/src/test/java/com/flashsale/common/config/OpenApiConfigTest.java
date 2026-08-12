package com.flashsale.common.config;

import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void usesBrowserOriginForSwaggerRequests() {
        var openApi = new OpenApiConfig().flashSaleOpenApi();

        assertThat(openApi.getServers())
            .extracting(server -> server.getUrl())
            .containsExactly("/");
    }

    @Test
    void registersBearerJwtSecuritySchemeSoSwaggerUiCanAuthorizeRequests() {
        var openApi = new OpenApiConfig().flashSaleOpenApi();

        var scheme = openApi.getComponents().getSecuritySchemes().get("bearerAuth");
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
        assertThat(scheme.getBearerFormat()).isEqualTo("JWT");

        assertThat(openApi.getSecurity())
            .flatExtracting(requirement -> requirement.keySet())
            .contains("bearerAuth");
    }
}
