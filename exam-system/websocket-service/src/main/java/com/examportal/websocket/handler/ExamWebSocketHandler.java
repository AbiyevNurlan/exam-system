package com.examportal.websocket.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exam WebSocket Handler
 * Handles real-time exam updates, notifications, and session management.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExamWebSocketHandler implements WebSocketHandler {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Sinks.Many<String>> examSinks = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        
        log.info("WebSocket connection established: {}", sessionId);

        // Handle incoming messages
        Mono<Void> input = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .doOnNext(message -> handleMessage(sessionId, message))
                .doOnError(error -> log.error("WebSocket error for session {}: {}", sessionId, error.getMessage()))
                .doFinally(signal -> {
                    sessions.remove(sessionId);
                    log.info("WebSocket connection closed: {} - Signal: {}", sessionId, signal);
                })
                .then();

        // Send heartbeat to keep connection alive
        Flux<WebSocketMessage> heartbeat = Flux.interval(java.time.Duration.ofSeconds(30))
                .map(tick -> session.textMessage("{\"type\":\"heartbeat\",\"timestamp\":" + System.currentTimeMillis() + "}"));

        Mono<Void> output = session.send(heartbeat);

        return Mono.zip(input, output).then();
    }

    private void handleMessage(String sessionId, String message) {
        log.debug("Received message from session {}: {}", sessionId, message);
        // Process message and broadcast to relevant exam participants
    }

    /**
     * Broadcast message to all connected sessions
     */
    public void broadcast(String message) {
        sessions.values().forEach(session -> {
            if (session.isOpen()) {
                session.send(Mono.just(session.textMessage(message))).subscribe();
            }
        });
    }

    /**
     * Send message to specific session
     */
    public void sendToSession(String sessionId, String message) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            session.send(Mono.just(session.textMessage(message))).subscribe();
        }
    }

    /**
     * Get count of active connections
     */
    public int getActiveConnectionCount() {
        return (int) sessions.values().stream().filter(WebSocketSession::isOpen).count();
    }
}
