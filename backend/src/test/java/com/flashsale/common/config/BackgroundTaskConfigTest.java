package com.flashsale.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundTaskConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(SchedulingConfig.class);

    @Test
    void schedulingIsEnabledByDefault() {
        contextRunner.run(context -> assertThat(context)
            .hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    void schedulingCanBeDisabledWithoutRemovingScheduledJobBeans() {
        contextRunner
            .withPropertyValues("app.scheduling.enabled=false")
            .run(context -> assertThat(context)
                .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }
}
