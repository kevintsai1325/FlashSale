package com.flashsale.order.adapter.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class OrderJpaRepositoryContractTest {

    @Test
    void findByIdLoadsItemsBeforeLeavingTheRepositoryTransaction() {
        assertItemsEntityGraph("findById", Long.class);
    }

    @Test
    void findAllByUserIdLoadsItemsBeforeLeavingTheRepositoryTransaction() {
        assertItemsEntityGraph("findAllByUserId", Long.class);
    }

    private void assertItemsEntityGraph(String methodName, Class<?>... parameterTypes) {
        Method method = Arrays.stream(OrderJpaRepository.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .filter(candidate -> Arrays.equals(candidate.getParameterTypes(), parameterTypes))
            .findFirst()
            .orElse(null);

        assertThat(method)
            .as("OrderJpaRepository.%s must declare its shopper fetch plan", methodName)
            .isNotNull();

        EntityGraph entityGraph = method.getAnnotation(EntityGraph.class);
        assertThat(entityGraph)
            .as("OrderJpaRepository.%s must initialize order items", methodName)
            .isNotNull();
        assertThat(entityGraph.attributePaths()).containsExactly("items");
    }
}
