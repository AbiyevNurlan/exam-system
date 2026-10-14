package com.examportal.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The instances behind the lb:// routes, from configuration instead of a registry:
 * gateway.services.&lt;service-id&gt; = comma separated base URLs (the production compose passes
 * AUTH_SERVICE_URL and EXAM_SERVICE_URL with one URL per container). The load balancer takes turns
 * over them. Without this the production profile had no instance for any lb:// route, and every
 * request ended in the circuit breaker fallback.
 *
 * Development routes use plain http:// URIs and do not go through here.
 */
@Slf4j
@Component
public class StaticDiscoveryClient implements ReactiveDiscoveryClient {

    private final Map<String, List<ServiceInstance>> instances;

    public StaticDiscoveryClient(Environment environment) {
        Map<String, String> configured = Binder.get(environment)
                .bind("gateway.services", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        Map<String, List<ServiceInstance>> result = new LinkedHashMap<>();
        configured.forEach((serviceId, urls) -> {
            List<ServiceInstance> list = new ArrayList<>();
            int n = 0;
            for (String url : urls.split(",")) {
                String trimmed = url.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                URI uri = URI.create(trimmed);
                boolean secure = "https".equalsIgnoreCase(uri.getScheme());
                int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
                list.add(new DefaultServiceInstance(serviceId + "-" + (++n), serviceId, uri.getHost(), port, secure));
            }
            result.put(serviceId, List.copyOf(list));
            log.info("Service {}: {} instance(s) {}", serviceId, list.size(), urls);
        });
        this.instances = Map.copyOf(result);
    }

    @Override
    public String description() {
        return "Static instances from gateway.services";
    }

    @Override
    public Flux<ServiceInstance> getInstances(String serviceId) {
        return Flux.fromIterable(instances.getOrDefault(serviceId, List.of()));
    }

    @Override
    public Flux<String> getServices() {
        return Flux.fromIterable(instances.keySet());
    }
}
