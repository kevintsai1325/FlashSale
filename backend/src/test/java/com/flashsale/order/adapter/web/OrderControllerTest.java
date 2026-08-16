package com.flashsale.order.adapter.web;

import com.flashsale.order.application.CancelOrderService;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.domain.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    OrderRepository orderRepository;

    @Mock
    CancelOrderService cancelOrderService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new OrderController(orderRepository, cancelOrderService))
            .setCustomArgumentResolvers(jwtArgumentResolver())
            .build();
    }

    @Test
    void myOrdersReturnsProductSnapshots() throws Exception {
        when(orderRepository.findAllByUserId(42L)).thenReturn(List.of(orderWithProductSnapshot()));

        mockMvc.perform(get("/api/orders/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].items[0].productName").value("限量鍵盤"))
            .andExpect(jsonPath("$[0].items[0].quantity").value(3))
            .andExpect(jsonPath("$[0].items[0].unitPrice").value(499.00));
    }

    @Test
    void detailReturnsProductSnapshots() throws Exception {
        when(orderRepository.findById(101L)).thenReturn(Optional.of(orderWithProductSnapshot()));

        mockMvc.perform(get("/api/orders/101"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].productName").value("限量鍵盤"))
            .andExpect(jsonPath("$.items[0].quantity").value(3))
            .andExpect(jsonPath("$.items[0].unitPrice").value(499.00));
    }

    @Test
    void cancelReturnsProductSnapshots() throws Exception {
        Order order = orderWithProductSnapshot();
        order.cancel();
        when(cancelOrderService.cancel(101L, 42L)).thenReturn(order);

        mockMvc.perform(post("/api/orders/101/cancel"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].productName").value("限量鍵盤"))
            .andExpect(jsonPath("$.items[0].quantity").value(3))
            .andExpect(jsonPath("$.items[0].unitPrice").value(499.00));
    }

    private Order orderWithProductSnapshot() {
        Order order = Order.createPendingPayment(
            42L, 2L, "限量鍵盤", 3, new BigDecimal("499.00"));
        ReflectionTestUtils.setField(order, "id", 101L);
        return order;
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
