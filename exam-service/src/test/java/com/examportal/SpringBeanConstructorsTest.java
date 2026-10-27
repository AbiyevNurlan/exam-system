package com.examportal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * Every Spring bean found by component scanning can be built: a class with more than one constructor marks the one
 * Spring uses with @Autowired, or has a constructor without parameters. Otherwise Spring looks for a default
 * constructor that is not there and the service does not start (2026-10-27: GuestOtpService, auth-service crashed on
 * the first deploy of B13a). No Spring context, no Docker: the classes are only read.
 */
class SpringBeanConstructorsTest {

    @Test
    void everyBeanWithSeveralConstructorsSaysWhichOneSpringUses() throws ClassNotFoundException {
        // @Component also finds @Service, @Repository, @Controller, @RestController and @Configuration
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<String> unclear = new ArrayList<>();
        int checked = 0;
        for (BeanDefinition definition : scanner.findCandidateComponents("com.examportal")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            checked++;
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length <= 1) {
                continue;
            }
            boolean autowired = Arrays.stream(constructors).anyMatch(c -> AnnotatedElementUtils.hasAnnotation(c, Autowired.class));
            boolean withoutParameters = Arrays.stream(constructors).anyMatch(c -> c.getParameterCount() == 0
                    && !Modifier.isPrivate(c.getModifiers()));
            if (!autowired && !withoutParameters) {
                unclear.add(type.getName() + " (" + constructors.length + " constructors, none @Autowired)");
            }
        }

        assertThat(checked).as("beans found by the scan").isGreaterThan(10);
        assertThat(unclear).as("beans Spring cannot build").isEmpty();
    }
}
