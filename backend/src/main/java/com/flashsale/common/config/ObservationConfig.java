package com.flashsale.common.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@code @Observed}-annotation aspect. Spring Boot auto-configures an
 * {@link ObservationRegistry} bean but does not register {@link ObservedAspect} itself — it has to
 * be added explicitly to activate the {@code @Observed} annotation on scheduled jobs.
 */
@Configuration
public class ObservationConfig {

    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }
}
