package com.examportal.exam.service;

import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.QuestionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exam Cache Service
 * Uses in-memory caching (ConcurrentHashMap) instead of Redis.
 * For production with multiple instances, Redis should be enabled.
 */
@Slf4j
@Service
public class ExamCacheService {

    private static final String EXAM_CONFIG_PREFIX = "exam:config:";
    private static final String SESSION_QUESTIONS_PREFIX = "exam:session:questions:";
    private static final String SESSION_DATA_PREFIX = "exam:session:data:";
    
    private static final Duration EXAM_CACHE_TTL = Duration.ofMinutes(30);
    
    // In-memory cache stores
    private final Map<String, CacheEntry<ExamResponse>> examCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<QuestionResponse>>> sessionQuestionsCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<Object>> sessionDataCache = new ConcurrentHashMap<>();
    
    private record CacheEntry<T>(T value, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * Cache exam configuration
     */
    public Mono<ExamResponse> cacheExam(ExamResponse exam) {
        String key = EXAM_CONFIG_PREFIX + exam.getId();
        log.debug("Caching exam config: {}", key);
        
        examCache.put(key, new CacheEntry<>(exam, Instant.now().plus(EXAM_CACHE_TTL)));
        log.debug("Exam cached successfully: {}", exam.getId());
        return Mono.just(exam);
    }

    /**
     * Get cached exam configuration
     */
    public Mono<ExamResponse> getCachedExam(Long examId) {
        String key = EXAM_CONFIG_PREFIX + examId;
        log.debug("Getting cached exam: {}", key);
        
        CacheEntry<ExamResponse> entry = examCache.get(key);
        if (entry != null && !entry.isExpired()) {
            log.debug("Cache hit for exam: {}", examId);
            return Mono.just(entry.value());
        } else {
            if (entry != null) {
                examCache.remove(key);
            }
            log.debug("Cache miss for exam: {}", examId);
            return Mono.empty();
        }
    }

    /**
     * Invalidate exam cache
     */
    public Mono<Void> invalidateExamCache(Long examId) {
        String key = EXAM_CONFIG_PREFIX + examId;
        log.debug("Invalidating exam cache: {}", key);
        examCache.remove(key);
        log.debug("Exam cache invalidated: {}", examId);
        return Mono.empty();
    }

    /**
     * Cache questions for an exam session
     */
    public Mono<Void> cacheSessionQuestions(String sessionId, List<QuestionResponse> questions, 
                                             int durationMinutes) {
        String key = SESSION_QUESTIONS_PREFIX + sessionId;
        Duration ttl = Duration.ofMinutes(durationMinutes + 30); // Add buffer
        
        log.debug("Caching {} questions for session: {}", questions.size(), sessionId);
        sessionQuestionsCache.put(key, new CacheEntry<>(questions, Instant.now().plus(ttl)));
        log.debug("Session questions cached: {}", sessionId);
        return Mono.empty();
    }

    /**
     * Get cached questions for a session with pagination
     */
    public Flux<QuestionResponse> getCachedQuestions(String sessionId, int batch, int batchSize) {
        String key = SESSION_QUESTIONS_PREFIX + sessionId;
        
        CacheEntry<List<QuestionResponse>> entry = sessionQuestionsCache.get(key);
        if (entry != null && !entry.isExpired()) {
            List<QuestionResponse> allQuestions = entry.value();
            int start = batch * batchSize;
            int end = Math.min(start + batchSize, allQuestions.size());
            
            if (start >= allQuestions.size()) {
                return Flux.empty();
            }
            
            return Flux.fromIterable(allQuestions.subList(start, end));
        }
        
        if (entry != null) {
            sessionQuestionsCache.remove(key);
        }
        return Flux.empty();
    }

    /**
     * Get all cached questions for a session
     */
    public Mono<List<QuestionResponse>> getAllCachedQuestions(String sessionId) {
        String key = SESSION_QUESTIONS_PREFIX + sessionId;
        
        CacheEntry<List<QuestionResponse>> entry = sessionQuestionsCache.get(key);
        if (entry != null && !entry.isExpired()) {
            return Mono.just(entry.value());
        }
        
        if (entry != null) {
            sessionQuestionsCache.remove(key);
        }
        return Mono.just(List.of());
    }

    /**
     * Cache session data
     */
    public Mono<Void> cacheSessionData(String sessionId, Object data, int durationMinutes) {
        String key = SESSION_DATA_PREFIX + sessionId;
        Duration ttl = Duration.ofMinutes(durationMinutes + 30);
        
        sessionDataCache.put(key, new CacheEntry<>(data, Instant.now().plus(ttl)));
        return Mono.empty();
    }

    /**
     * Get cached session data
     */
    public Mono<Object> getCachedSessionData(String sessionId) {
        String key = SESSION_DATA_PREFIX + sessionId;
        
        CacheEntry<Object> entry = sessionDataCache.get(key);
        if (entry != null && !entry.isExpired()) {
            return Mono.just(entry.value());
        }
        
        if (entry != null) {
            sessionDataCache.remove(key);
        }
        return Mono.empty();
    }

    /**
     * Invalidate session cache
     */
    public Mono<Void> invalidateSessionCache(String sessionId) {
        sessionQuestionsCache.remove(SESSION_QUESTIONS_PREFIX + sessionId);
        sessionDataCache.remove(SESSION_DATA_PREFIX + sessionId);
        return Mono.empty();
    }

    /**
     * Extend session cache TTL
     */
    public Mono<Boolean> extendSessionTTL(String sessionId, int additionalMinutes) {
        String key = SESSION_QUESTIONS_PREFIX + sessionId;
        Duration additionalTTL = Duration.ofMinutes(additionalMinutes);
        
        CacheEntry<List<QuestionResponse>> entry = sessionQuestionsCache.get(key);
        if (entry != null && !entry.isExpired()) {
            Instant newExpiry = entry.expiresAt().plus(additionalTTL);
            sessionQuestionsCache.put(key, new CacheEntry<>(entry.value(), newExpiry));
            return Mono.just(true);
        }
        return Mono.just(false);
    }
    
    /**
     * Clean up expired cache entries (should be called periodically)
     */
    public void cleanupExpiredEntries() {
        log.debug("Cleaning up expired cache entries...");
        
        examCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        sessionQuestionsCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        sessionDataCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        
        log.debug("Cache cleanup complete. Exam: {}, Questions: {}, Data: {}", 
                examCache.size(), sessionQuestionsCache.size(), sessionDataCache.size());
    }
}
