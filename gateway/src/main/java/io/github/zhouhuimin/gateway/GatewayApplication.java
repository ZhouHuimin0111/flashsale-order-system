package io.github.zhouhuimin.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }
    @Bean RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes().route("orders", r -> r.path("/api/**").uri("lb://order-service")).build();
    }
}
