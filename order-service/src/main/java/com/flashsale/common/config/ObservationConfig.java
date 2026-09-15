package com.flashsale.common.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@code @Observed}-annotation aspect. Spring Boot 3.3+ already auto-configures
 * {@link ObservedAspect} via {@code ObservationAutoConfiguration$ObservedAspectConfiguration}
 * when {@code aspectjweaver} is on the classpath (which {@code spring-boot-starter-aop} provides
 * here), guarded by {@code @ConditionalOnMissingBean} — so this explicit bean is not filling a
 * gap that would otherwise be missing. It's kept as a defensive/explicit registration so
 * {@code @Observed} on scheduled jobs doesn't depend on undocumented auto-configuration behavior
 * that could change across Boot versions; {@code @ConditionalOnMissingBean} on Boot's side means
 * at most one of the two ever wins.
 */
@Configuration
public class ObservationConfig {

    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }
}
