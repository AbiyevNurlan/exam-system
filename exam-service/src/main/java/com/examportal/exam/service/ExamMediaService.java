package com.examportal.exam.service;

import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.OptionDTO;
import com.examportal.common.domain.dto.OptionResponse;
import com.examportal.common.domain.dto.QuestionDTO;
import com.examportal.common.domain.dto.QuestionResponse;
import com.examportal.common.domain.dto.media.MediaItemResponse;
import com.examportal.common.domain.dto.media.QuestionMediaRef;
import com.examportal.common.domain.entity.MediaAsset;
import com.examportal.common.domain.entity.MediaKind;
import com.examportal.common.domain.entity.MediaStatus;
import com.examportal.exam.exception.MediaException;
import com.examportal.exam.repository.MediaAssetRepository;
import com.examportal.exam.repository.MediaLinkRepository;
import com.examportal.exam.repository.MediaLinkRepository.AttachedMedia;
import com.examportal.exam.storage.MediaStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Exam Media Service
 * Media on the questions and options of an exam: checking what an exam request wants to attach,
 * writing the links (inside the caller's transaction) and adding the media to exam responses.
 *
 * Two views, decided by the caller:
 * - ADMIN and the INSTRUCTOR who owns the exam get a short-lived previewUrl per media.
 * - Participants get the metadata only. A URL is handed out per media through the session
 *   endpoint (SessionMediaService), never inside an exam or question response.
 */
@Service
@RequiredArgsConstructor
public class ExamMediaService {

    private final MediaAssetRepository assetRepository;
    private final MediaLinkRepository linkRepository;
    private final MediaStorage storage;

    @Value("${exam.media.preview-url-ttl-seconds:300}")
    private int previewUrlTtlSeconds;

    /**
     * Media of one exam by question id and by option id
     */
    public record MediaIndex(Map<Long, List<MediaItemResponse>> byQuestion, Map<Long, List<MediaItemResponse>> byOption) {

        public List<MediaItemResponse> forQuestion(Long questionId) {
            return byQuestion.get(questionId);
        }

        public List<MediaItemResponse> forOption(Long optionId) {
            return byOption.get(optionId);
        }

        public boolean isEmpty() {
            return byQuestion.isEmpty() && byOption.isEmpty();
        }
    }

    /**
     * Check every media reference of an exam request before anything is written:
     * the asset exists, is ACTIVE and may be attached by the editor; a question has no media twice
     * and a play limit only on audio or video. An option carries one image, sound or video (no play limit).
     *
     * What an editor may attach: the own media, for ADMIN everybody's, and whatever the exam already
     * carries, whoever uploaded it. So the owner can save (or drop) media an ADMIN added to the
     * exam, but cannot bring in someone else's media that is not part of it yet.
     * examId is null for an exam that is being created.
     */
    public Mono<Void> validate(List<QuestionDTO> questions, UUID userId, String role, Long examId) {
        Set<UUID> ids = new HashSet<>();
        for (QuestionDTO question : questions) {
            if (question.getMedia() != null) {
                Set<UUID> inQuestion = new HashSet<>();
                for (QuestionMediaRef ref : question.getMedia()) {
                    if (!inQuestion.add(ref.getMediaId())) {
                        return Mono.error(MediaException.notAttachable("a question lists the same media twice"));
                    }
                }
                ids.addAll(inQuestion);
            }
            if (question.getOptions() != null) {
                for (OptionDTO option : question.getOptions()) {
                    if (option.getMediaIds() != null) {
                        ids.addAll(option.getMediaIds());
                    }
                }
            }
        }
        if (ids.isEmpty()) {
            return Mono.empty();
        }

        boolean admin = "ADMIN".equals(role);
        // Media the exam already carries; not needed for ADMIN (everything) or a new exam (nothing)
        Mono<Set<UUID>> alreadyAttached = admin || examId == null
                ? Mono.just(Set.<UUID>of())
                : linkRepository.findByExamId(examId).map(AttachedMedia::mediaId).collect(Collectors.toSet());

        return alreadyAttached.flatMap(attached -> assetRepository.findByIdIn(ids)
                .filter(asset -> asset.getStatus() == MediaStatus.ACTIVE)
                .filter(asset -> admin || asset.getOwnerId().equals(userId) || attached.contains(asset.getId()))
                .collect(Collectors.toMap(MediaAsset::getId, Function.identity())))
                .flatMap(usable -> {
                    // One answer for unknown, unconfirmed, deleted and someone else's media
                    if (usable.size() != ids.size()) {
                        return Mono.error(MediaException.notAttachable("it does not exist, is not confirmed or is not yours"));
                    }
                    for (QuestionDTO question : questions) {
                        if (question.getMedia() != null) {
                            for (QuestionMediaRef ref : question.getMedia()) {
                                if (ref.getMaxPlays() != null && usable.get(ref.getMediaId()).getKind() == MediaKind.IMAGE) {
                                    return Mono.error(MediaException.notAttachable("maxPlays is for audio and video only"));
                                }
                            }
                        }
                    }
                    return Mono.empty();
                });
    }

    /**
     * Write the media links of a saved question; part of the caller's transaction
     */
    public Mono<Void> attachToQuestion(Long questionId, QuestionDTO question) {
        if (question.getMedia() == null || question.getMedia().isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(question.getMedia())
                .index()
                // Sequential: these writes share the one connection of the surrounding transaction
                .concatMap(tuple -> {
                    QuestionMediaRef ref = tuple.getT2();
                    int position = ref.getPosition() != null ? ref.getPosition() : tuple.getT1().intValue();
                    return linkRepository.attachToQuestion(questionId, ref.getMediaId(), position, ref.getMaxPlays())
                            .flatMap(this::requireAttached);
                })
                .then();
    }

    /**
     * Write the media links of a saved option; part of the caller's transaction
     */
    public Mono<Void> attachToOption(Long optionId, OptionDTO option) {
        if (option.getMediaIds() == null || option.getMediaIds().isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(option.getMediaIds())
                .concatMap(mediaId -> linkRepository.attachToOption(optionId, mediaId)
                        .flatMap(this::requireAttached))
                .then();
    }

    /**
     * A media that stopped being ACTIVE between validate and the write (deleted, or cleaned up as
     * an orphan) fails the whole save: the surrounding transaction rolls back
     */
    private Mono<Long> requireAttached(Long written) {
        return written == 1
                ? Mono.just(written)
                : Mono.error(MediaException.notAttachable("it was deleted in the meantime"));
    }

    /**
     * Media of an exam for the participant view: metadata only, never a URL
     */
    public Mono<MediaIndex> loadIndex(Long examId) {
        return loadIndex(examId, false);
    }

    /**
     * Media of an exam with a short-lived previewUrl each: only for callers who manage the exam
     */
    public Mono<MediaIndex> loadIndexWithPreviews(Long examId) {
        return loadIndex(examId, true);
    }

    /**
     * A copy of the exam response with the media of its questions and options.
     * The given response is not changed: it may be the cached instance, which never carries media.
     */
    public Mono<ExamResponse> withMedia(ExamResponse exam, boolean withPreviewUrls) {
        if (exam.getQuestions() == null || exam.getQuestions().isEmpty()) {
            return Mono.just(exam);
        }
        return loadIndex(exam.getId(), withPreviewUrls)
                .map(index -> {
                    if (index.isEmpty()) {
                        return exam;
                    }
                    List<QuestionResponse> questions = exam.getQuestions().stream()
                            .map(question -> question.toBuilder()
                                    .media(index.forQuestion(question.getId()))
                                    .options(question.getOptions() == null ? null : question.getOptions().stream()
                                            .map(option -> withMedia(option, index))
                                            .toList())
                                    .build())
                            .toList();
                    return exam.toBuilder().questions(questions).build();
                });
    }

    // ==================== Private Helper Methods ====================

    private OptionResponse withMedia(OptionResponse option, MediaIndex index) {
        List<MediaItemResponse> media = index.forOption(option.getId());
        return media == null ? option : option.toBuilder().media(media).build();
    }

    private Mono<MediaIndex> loadIndex(Long examId, boolean withPreviewUrls) {
        // Without storage settings the owner still sees which media are attached, just no preview
        boolean sign = withPreviewUrls && storage.isConfigured();
        return linkRepository.findByExamId(examId)
                .collectList()
                .map(links -> {
                    Map<Long, List<MediaItemResponse>> byQuestion = new HashMap<>();
                    Map<Long, List<MediaItemResponse>> byOption = new HashMap<>();
                    for (AttachedMedia link : links) {
                        boolean onQuestion = MediaLinkRepository.TARGET_QUESTION.equals(link.target());
                        MediaItemResponse item = MediaItemResponse.builder()
                                .id(link.mediaId())
                                .kind(link.kind())
                                .altText(link.altText())
                                .durationSeconds(link.durationSeconds())
                                .position(onQuestion ? link.position() : null)
                                .maxPlays(link.maxPlays())
                                .previewUrl(sign
                                        ? storage.presignDownload(link.storageKey(), Duration.ofSeconds(previewUrlTtlSeconds))
                                        : null)
                                .build();
                        (onQuestion ? byQuestion : byOption)
                                .computeIfAbsent(link.targetId(), id -> new ArrayList<>())
                                .add(item);
                    }
                    return new MediaIndex(byQuestion, byOption);
                });
    }
}
