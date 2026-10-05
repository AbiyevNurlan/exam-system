package com.examportal.exam.service;

import com.examportal.exam.repository.ExamSessionRepository;
import com.examportal.exam.repository.UserAnswerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Anti-Cheating & Fraud Detection Service
 * Detects suspicious behavior during exams to ensure integrity.
 * Uses in-memory storage for single-instance deployment.
 * 
 * Detection Methods:
 * 1. Tab switching detection
 * 2. Copy-paste detection
 * 3. Rapid answer pattern detection
 * 4. IP change detection
 * 5. Browser fingerprint change detection
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FraudDetectionService {

    private final ExamSessionRepository sessionRepository;
    private final UserAnswerRepository answerRepository;

    // In-memory storage for fraud tracking
    private final Map<String, AtomicInteger> tabSwitchCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> copyPasteCounts = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> ipAddresses = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> browserFingerprints = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> answerTimes = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> fraudIncidents = new ConcurrentHashMap<>();
    
    /**
     * Track tab switch event
     */
    public Mono<Void> trackTabSwitch(String sessionId) {
        int count = tabSwitchCounts
                .computeIfAbsent(sessionId, k -> new AtomicInteger(0))
                .incrementAndGet();
        
        // Alert if excessive tab switching (>5 times)
        if (count > 5) {
            log.warn("FRAUD ALERT: Session {} has {} tab switches", sessionId, count);
            return recordFraudIncident(sessionId, "EXCESSIVE_TAB_SWITCHING", 
                    "User switched tabs " + count + " times");
        }
        return Mono.empty();
    }

    /**
     * Track copy-paste event
     */
    public Mono<Void> trackCopyPaste(String sessionId, String questionId) {
        int count = copyPasteCounts
                .computeIfAbsent(sessionId, k -> new AtomicInteger(0))
                .incrementAndGet();
        
        // Alert if excessive copy-paste (>3 times)
        if (count > 3) {
            log.warn("FRAUD ALERT: Session {} has {} copy-paste events", sessionId, count);
            return recordFraudIncident(sessionId, "EXCESSIVE_COPY_PASTE", 
                    "User copy-pasted " + count + " times");
        }
        return Mono.empty();
    }

    /**
     * Track rapid answer pattern (bot detection)
     */
    public Mono<Void> trackAnswerTiming(String sessionId, Long questionId, long responseTimeMs) {
        List<Long> times = answerTimes.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        times.add(responseTimeMs);
        
        // Check for suspiciously fast answers
        if (responseTimeMs < 1000) { // Less than 1 second
            // Get last 10 answers
            int size = times.size();
            int start = Math.max(0, size - 10);
            List<Long> recentTimes = times.subList(start, size);
            
            long fastAnswers = recentTimes.stream()
                    .filter(t -> t < 1000)
                    .count();
            
            // More than 5 answers under 1 second
            if (fastAnswers > 5) {
                log.warn("FRAUD ALERT: Session {} has {} rapid answers", sessionId, fastAnswers);
                return recordFraudIncident(sessionId, "RAPID_ANSWER_PATTERN", 
                        "User answered " + fastAnswers + " questions in <1s each");
            }
        }
        return Mono.empty();
    }

    /**
     * Track IP address change
     */
    public Mono<Void> trackIpChange(String sessionId, String newIp) {
        Set<String> ips = ipAddresses.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        ips.add(newIp);
        
        // Alert if IP changed more than once
        if (ips.size() > 2) {
            log.warn("FRAUD ALERT: Session {} accessed from {} different IPs", sessionId, ips.size());
            return recordFraudIncident(sessionId, "MULTIPLE_IP_ADDRESSES", 
                    "Session accessed from " + ips.size() + " different IPs");
        }
        return Mono.empty();
    }

    /**
     * Track browser fingerprint change
     */
    public Mono<Void> trackBrowserChange(String sessionId, String fingerprint) {
        Set<String> fingerprints = browserFingerprints.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        fingerprints.add(fingerprint);
        
        // Alert if fingerprint changed
        if (fingerprints.size() > 1) {
            log.warn("FRAUD ALERT: Session {} has {} different browser fingerprints", 
                    sessionId, fingerprints.size());
            return recordFraudIncident(sessionId, "BROWSER_FINGERPRINT_CHANGE", 
                    "Different browser or device detected");
        }
        return Mono.empty();
    }

    /**
     * Get fraud score for a session (0-100)
     */
    public Mono<Integer> calculateFraudScore(String sessionId) {
        int tabSwitches = tabSwitchCounts.getOrDefault(sessionId, new AtomicInteger(0)).get();
        int copyPastes = copyPasteCounts.getOrDefault(sessionId, new AtomicInteger(0)).get();
        int ipCount = ipAddresses.getOrDefault(sessionId, Set.of()).size();
        int fingerprintCount = browserFingerprints.getOrDefault(sessionId, Set.of()).size();
        int fastAnswers = getFastAnswerCount(sessionId);
        
        int score = 0;
        score += Math.min(tabSwitches * 10, 30);        // Max 30 points
        score += Math.min(copyPastes * 15, 30);         // Max 30 points
        score += Math.min((ipCount - 1) * 20, 20);      // Max 20 points
        score += Math.min((fingerprintCount - 1) * 20, 20); // Max 20 points
        score += Math.min(fastAnswers * 2, 20);         // Max 20 points
        
        return Mono.just(Math.min(score, 100));
    }

    /**
     * Get fraud report for a session
     */
    public Mono<Map<String, Object>> getFraudReport(String sessionId) {
        int tabSwitches = tabSwitchCounts.getOrDefault(sessionId, new AtomicInteger(0)).get();
        int copyPastes = copyPasteCounts.getOrDefault(sessionId, new AtomicInteger(0)).get();
        int ipCount = ipAddresses.getOrDefault(sessionId, Set.of()).size();
        int fingerprintCount = browserFingerprints.getOrDefault(sessionId, Set.of()).size();
        int fastAnswers = getFastAnswerCount(sessionId);
        
        return calculateFraudScore(sessionId).map(fraudScore -> {
            Map<String, Object> report = new HashMap<>();
            report.put("sessionId", sessionId);
            report.put("tabSwitches", tabSwitches);
            report.put("copyPasteEvents", copyPastes);
            report.put("ipAddressCount", ipCount);
            report.put("browserFingerprintCount", fingerprintCount);
            report.put("rapidAnswers", fastAnswers);
            report.put("fraudScore", fraudScore);
            report.put("incidents", fraudIncidents.getOrDefault(sessionId, List.of()));
            report.put("riskLevel", getRiskLevel(fraudScore));
            report.put("timestamp", LocalDateTime.now());
            
            return report;
        });
    }

    // ==================== Private Helper Methods ====================

    private int getFastAnswerCount(String sessionId) {
        List<Long> times = answerTimes.getOrDefault(sessionId, List.of());
        return (int) times.stream()
                .filter(t -> t < 1000)
                .count();
    }

    private Mono<Void> recordFraudIncident(String sessionId, String type, String description) {
        List<Map<String, Object>> incidents = fraudIncidents
                .computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        
        Map<String, Object> incident = new HashMap<>();
        incident.put("type", type);
        incident.put("description", description);
        incident.put("timestamp", LocalDateTime.now().toString());
        
        incidents.add(incident);
        return Mono.empty();
    }

    private String getRiskLevel(int score) {
        if (score >= 70) return "HIGH";
        if (score >= 40) return "MEDIUM";
        if (score >= 20) return "LOW";
        return "MINIMAL";
    }
    
    /**
     * Clean up session data (should be called when session ends)
     */
    public void cleanupSession(String sessionId) {
        tabSwitchCounts.remove(sessionId);
        copyPasteCounts.remove(sessionId);
        ipAddresses.remove(sessionId);
        browserFingerprints.remove(sessionId);
        answerTimes.remove(sessionId);
        fraudIncidents.remove(sessionId);
    }
}
