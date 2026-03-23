package com.examportal.websocket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;

/**
 * WebSocket Service Application
 * Real-time communication service for live exam updates and notifications.
 */
@SpringBootApplication(
    scanBasePackages = {"com.examportal.websocket", "com.examportal.common"},
    exclude = {R2dbcAutoConfiguration.class, R2dbcTransactionManagerAutoConfiguration.class}
)
public class WebSocketServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebSocketServiceApplication.class, args);
    }
}
