package io.github.zhouhuimin.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import java.time.Duration;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }
    @Bean RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes().route("orders", r -> r.path("/api/**").uri("lb://order-service")).build();
    }
    @Bean HttpClientCustomizer containerDnsRefresh() {
        // Docker may assign a different address on restart. Honor short-lived
        // discovery rather than retaining Docker DNS's long positive TTL.
        return client -> client.resolver(spec -> spec.cacheMinTimeToLive(Duration.ZERO)
            .cacheMaxTimeToLive(Duration.ofSeconds(5)).cacheNegativeTimeToLive(Duration.ZERO));
    }
}
