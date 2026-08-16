package com.flashsale.common.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Prevents Boot from also registering the security-chain audit filter as a Servlet filter. */
@Configuration
public class ApiAuditFilterRegistrationConfig {

    @Bean
    FilterRegistrationBean<ApiAuditFilter> apiAuditFilterRegistration(ApiAuditFilter filter) {
        FilterRegistrationBean<ApiAuditFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
