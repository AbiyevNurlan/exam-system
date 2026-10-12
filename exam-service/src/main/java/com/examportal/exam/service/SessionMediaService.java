package com.examportal.exam.service;

import com.examportal.common.domain.dto.media.MediaUrlResponse;
import com.examportal.common.domain.entity.ExamSession;
import com.examportal.common.domain.entity.MediaKind;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.exception.MediaException;
import com.examportal.exam.repository.ExamSessionRepository;
import com.examportal.exam.repository.MediaLinkRepository;
import com.examportal.exam.repository.MediaLinkRepository.AttachedMedia;
import com.examportal.exam.storage.MediaStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Session Media Service
 * The only way a participant gets a media file: a short-lived presigned URL, handed out inside the
 * participant's own running session, for media that belongs to that session's exam.
 *
 * - Someone else's session is a 404, like an unknown one (ExamAccessPolicy.requireOwnSession). The
 *   session is the proof of access: it could only be started with access to the exam, and a running
 *   session stays usable after the user lost access, like every other session endpoint.
 * - A session that ran out of time or is completed is refused exactly like an answer would be.
 * - Media of another exam is a 404, like unknown media.
 * - Play limits (audio/video with maxPlays) are counted on the server per session, in one atomic
 *   SQL statement; every URL handed out counts as one play.
 * - URLs are never logged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionMediaService {

    private final ExamSessionRepository sessionRepository;
    private final MediaLinkRepository linkRepository;
    private final ExamAccessPolicy accessPolicy;
    private final AnswerSubmissionService answerService;
    private final MediaStorage storage;

    @Value("${exam.media.session-url-ttl-seconds:300}")
    private int sessionUrlTtlSeconds;

    public Mono<MediaUrlResponse> getMediaUrl(String sessionId, UUID mediaId, UUID userId) {
        return sessionRepository.findBySessionId(sessionId)
                .switchIfEmpty(Mono.error(ExamException.sessionNotFound(sessionId)))
                .flatMap(session -> accessPolicy.requireOwnSession(userId, session))
                .<ExamSession>flatMap(session -> {
                    if (session.isExpired()) {
                        // Same as an answer after the end time: the session is completed, then refused
                        return answerService.completeExpiredSession(session)
                                .then(Mono.<ExamSession>error(ExamException.sessionExpired()));
                    }
                    if (!session.isActive()) {
                        return Mono.error(ExamException.sessionAlreadyCompleted());
                    }
                    return Mono.just(session);
                })
                .flatMap(session -> linkRepository.findInExam(session.getExamId(), mediaId)
                        .switchIfEmpty(Mono.error(MediaException.notFound()))
                        .flatMap(media -> {
                            // Before a play is counted: a URL that cannot be signed must not cost one
                            if (!storage.isConfigured()) {
                                return Mono.error(MediaException.storageUnavailable(null));
                            }
                            return countPlay(session, media)
                                    .map(remaining -> toResponse(media, remaining))
                                    .switchIfEmpty(Mono.fromSupplier(() -> toResponse(media, null)));
                        }))
                .doOnNext(response -> log.debug("Media {} handed out in session {}", mediaId, sessionId));
    }

    // ==================== Private Helper Methods ====================

    /**
     * Counts a play of limited audio/video and emits the plays that are left; empty for unlimited
     * media and images; 409 when the limit is used up.
     */
    private Mono<Integer> countPlay(ExamSession session, AttachedMedia media) {
        if (media.kind() == MediaKind.IMAGE || media.maxPlays() == null) {
            return Mono.empty();
        }
        return linkRepository.registerPlay(session.getSessionId(), media.mediaId(), media.maxPlays())
                .switchIfEmpty(Mono.error(MediaException.playsExhausted()))
                .map(plays -> media.maxPlays() - plays);
    }

    private MediaUrlResponse toResponse(AttachedMedia media, Integer remainingPlays) {
        return MediaUrlResponse.builder()
                .url(storage.presignDownload(media.storageKey(), Duration.ofSeconds(sessionUrlTtlSeconds)))
                .kind(media.kind())
                .expiresIn(sessionUrlTtlSeconds)
                .remainingPlays(remainingPlays)
                .build();
    }
}