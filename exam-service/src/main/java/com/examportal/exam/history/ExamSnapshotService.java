package com.examportal.exam.history;

import com.examportal.exam.structure.ExamStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The snapshot of a publication (B12a): the whole exam as it is published (settings, audience, blocks,
 * questions, options with the answer key, media ids) as canonical JSON with its SHA-256, a new version per
 * publication. Called inside the publishing transaction, after the status change: the stored content is
 * exactly what was published. Emits {versionNo, sha256} for the PUBLISHED history row.
 */
@Service
@RequiredArgsConstructor
public class ExamSnapshotService {

    private final ExamStateRepository states;
    private final ExamSnapshotRepository snapshots;

    public Mono<Map<String, Object>> publish(Long examId, ChangeContext context) {
        return states.load(examId)
                .flatMap(state -> {
                    String canonical = ExamSnapshots.canonicalJson(ExamSnapshots.exam(state));
                    String sha256 = ExamSnapshots.sha256(canonical);
                    return snapshots.append(examId, canonical, sha256, context)
                            .map(version -> {
                                Map<String, Object> published = new LinkedHashMap<>();
                                published.put("versionNo", version);
                                published.put("sha256", sha256);
                                return published;
                            });
                });
    }

    /**
     * Whether the stored snapshot still has its hash (JSONB reorders keys; the canonical form does not change)
     */
    public static boolean verify(ExamSnapshotRepository.Snapshot snapshot) {
        return snapshot.snapshotJson() != null
                && ExamSnapshots.sha256(ExamSnapshots.canonicalize(snapshot.snapshotJson())).equals(snapshot.sha256());
    }
}
