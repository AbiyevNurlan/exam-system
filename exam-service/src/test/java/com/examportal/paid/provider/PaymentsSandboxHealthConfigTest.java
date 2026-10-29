package com.examportal.paid.provider;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.endpoint.ApiVersion;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.ReactiveHealthEndpointWebExtension;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The health settings of application.yml and application-production.yml with the real guard and health component, as
 * an anonymous web caller sees them (go-public.ps1 and docker exec wget ask without a token): in production a sandbox
 * asked for leaves the overall health UP without components (when_authorized, as before) and shows WARNING with its
 * details at /actuator/health/payments; in development the component is UP in the overall health with the others.
 */
class PaymentsSandboxHealthConfigTest {

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(EndpointAutoConfiguration.class, WebEndpointAutoConfiguration.class,
                    HealthContributorAutoConfiguration.class, HealthEndpointAutoConfiguration.class))
            .withBean(SandboxProductionGuard.class)
            .withBean("paymentsSandboxHealthIndicator", PaymentsSandboxHealthIndicator.class);

    private static HealthComponent anonymous(ReactiveHealthEndpointWebExtension health, String... path) {
        WebEndpointResponse<? extends HealthComponent> response = health.health(ApiVersion.V3, WebServerNamespace.SERVER,
                SecurityContext.NONE, path).block();
        assertThat(response.getStatus()).isEqualTo(200);
        return response.getBody();
    }

    private static Health component(HealthComponent health) {
        assertThat(health).isInstanceOf(CompositeHealth.class);
        return (Health) ((CompositeHealth) health).getComponents().get("paymentsSandbox");
    }

    @Test
    void inProductionTheGroupWarnsAndTheServiceStaysUp() {
        runner.withPropertyValues("spring.profiles.active=production", "payments.sandbox.allowed=true", "features.paid-access.enabled=true",
                        "payments.provider=SANDBOX")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ReactiveHealthEndpointWebExtension health = context.getBean(ReactiveHealthEndpointWebExtension.class);
                    HealthComponent overall = anonymous(health);
                    assertThat(overall.getStatus()).isEqualTo(Status.UP);
                    // when_authorized of the production profile: no components for a caller without a role
                    assertThat(overall instanceof CompositeHealth composite ? composite.getComponents() : null).isNullOrEmpty();

                    HealthComponent group = anonymous(health, "payments");
                    assertThat(group.getStatus()).isEqualTo(PaymentsSandboxHealthIndicator.WARNING);
                    assertThat(component(group).getDetails()).containsEntry("sandbox", "BLOCKED_IN_PRODUCTION")
                            .containsEntry("warning", SandboxProductionGuard.MESSAGE);
                });
    }

    @Test
    void inProductionWithoutTheSandboxTheGroupIsUp() {
        runner.withPropertyValues("spring.profiles.active=production", "payments.sandbox.allowed=false", "features.paid-access.enabled=false")
                .run(context -> {
                    ReactiveHealthEndpointWebExtension health = context.getBean(ReactiveHealthEndpointWebExtension.class);
                    assertThat(anonymous(health).getStatus()).isEqualTo(Status.UP);
                    HealthComponent group = anonymous(health, "payments");
                    assertThat(group.getStatus()).isEqualTo(Status.UP);
                    assertThat(component(group).getDetails()).containsEntry("sandbox", "OFF");
                });
    }

    @Test
    void inDevelopmentTheComponentIsUpWithTheOthers() {
        runner.withPropertyValues("payments.sandbox.allowed=true", "features.paid-access.enabled=true").run(context -> {
            HealthComponent overall = anonymous(context.getBean(ReactiveHealthEndpointWebExtension.class));
            assertThat(overall.getStatus()).isEqualTo(Status.UP);
            Health sandbox = component(overall);
            assertThat(sandbox.getStatus()).isEqualTo(Status.UP);
            assertThat(sandbox.getDetails()).containsEntry("sandbox", "ON");
        });
    }
}
