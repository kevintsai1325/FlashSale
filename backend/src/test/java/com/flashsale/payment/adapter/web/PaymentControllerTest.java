package com.flashsale.payment.adapter.web;

import com.flashsale.order.domain.Order;
import com.flashsale.payment.application.SubmitPaymentService;
import com.flashsale.payment.domain.PaymentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock
    SubmitPaymentService submitPaymentService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new PaymentController(submitPaymentService))
            .setCustomArgumentResolvers(jwtArgumentResolver())
            .build();
    }

    @Test
    void submitPaymentReturnsProductSnapshots() throws Exception {
        Order order = Order.createPendingPayment(
            42L, 2L, "限量鍵盤", 3, new BigDecimal("499.00"));
        order.pay();
        when(submitPaymentService.submit(101L, 42L, PaymentResult.SUCCESS)).thenReturn(order);

        mockMvc.perform(post("/api/orders/101/payments")
                .contentType(APPLICATION_JSON)
                .content("{\"result\":\"SUCCESS\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].productName").value("限量鍵盤"))
            .andExpect(jsonPath("$.items[0].quantity").value(3))
            .andExpect(jsonPath("$.items[0].unitPrice").value(499.00));
    }

    private HandlerMethodArgumentResolver jwtArgumentResolver() {
        Jwt jwt = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .claim("userId", 42L)
            .build();
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType().equals(Jwt.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest,
                                          org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
                return jwt;
            }
        };
    }
}
