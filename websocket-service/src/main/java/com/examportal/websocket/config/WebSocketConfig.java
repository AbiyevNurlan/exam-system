package com.examportal.websocket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

/**
 * WebSocket Configuration
 * Configures WebSocket handlers and mappings for real-time communication.
 */
@Configuration
public class WebSocketConfig {

    @Bean
    public WebSocketHandlerAdapter handlerAdapter() {
        return new WebSocketHandlerAdapter();
    }

    @Bean
    public HandlerMapping webSocketHandlerMapping(WebSocketHandler examWebSocketHandler) {
        Map<String, WebSocketHandler> handlerMap = Map.of(
                "/ws/exam/**", examWebSocketHandler,
                "/ws/notifications/**", examWebSocketHandler
        );

        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(1);
        mapping.setUrlMap(handlerMap);
        return mapping;
    }
}
