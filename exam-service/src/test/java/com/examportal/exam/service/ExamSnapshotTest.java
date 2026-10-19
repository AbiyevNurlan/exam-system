package com.examportal.exam.service;

import com.examportal.common.domain.dto.CreateExamRequest;
import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.QuestionDTO;
import com.examportal.common.domain.dto.RescheduleExamRequest;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.exam.history.ExamSnapshotRepository;
import com.examportal.exam.history.ExamSnapshotService;
import com.examportal.exam.history.ExamSnapshots;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B12a, item 3e, on a real PostgreSQL: every publication stores the whole exam as a new version (1, 2, ...)
 * with the SHA-256 of its canonical JSON; the hash still matches after the round trip through JSONB (keys
 * reordered, numbers like 2.00 kept); the PUBLISHED history row names version and hash; a changed exam
 * published again gets a different hash. Skipped without Docker.
 */
class ExamSnapshotTest {

    // The owner the exams are created by (ExamHistoryTest.nurlan())
    private static final UUID OWNER = ExamHistoryTest.nurlan().actorId();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static ExamTestStack stack;
    private static ExamService service;
    private static ExamSnapshotRepository snapshots;

    @BeforeAll
    static void start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        stack = ExamTestStack.start();
        service = stack.examService(new ExamFeatures(false, false));
        snapshots = new ExamSnapshotRepository(stack.db);
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    void everyPublicationIsAVersionWithItsHash() throws Exception {
        CreateExamRequest request = ExamBlocksServiceTest.create("T-b12a snapshot");
        QuestionDTO decimal = ExamHistoryTest.question(null, "Ə, ş, ğ və 2.50 bal", 2, "2.50");
        request.setQuestions(new ArrayList<>(List.of(decimal, ExamHistoryTest.question(null, "İkinci", 0, "1"))));
        ExamResponse exam = service.createExam(request, ExamHistoryTest.nurlan()).block();

        service.updateExamStatus(exam.getId(), ExamStatus.ACTIVE, ExamHistoryTest.nurlan()).block();
        ExamSnapshotRepository.Snapshot first = snapshots.find(exam.getId(), 1).block();
        assertThat(first.versionNo()).isEqualTo(1);
        assertThat(first.sha256()).matches("[0-9a-f]{64}");
        assertThat(first.actorName()).isEqualTo("Nurlan Abiyev");
        assertThat(first.actorCode()).isEqualTo(1234567);
        // Stored as JSONB (keys reordered, spaces added) and still the same canonical content
        assertThat(first.snapshotJson()).isNotEqualTo(ExamSnapshots.canonicalize(first.snapshotJson()));
        assertThat(ExamSnapshotService.verify(first)).isTrue();
        JsonNode content = JSON.readTree(first.snapshotJson());
        JsonNode question = content.get("blocks").get(0).get("questions").get(0);
        assertThat(question.get("points").decimalValue()).isEqualByComparingTo("2.50");
        assertThat(question.get("text").asText()).isEqualTo("Ə, ş, ğ və 2.50 bal");
        assertThat(question.get("options").get(2).get("correct").asBoolean()).isTrue();
        assertThat(content.get("status").asText()).isEqualTo("ACTIVE");

        // The PUBLISHED row names the version and the hash
        String params = stack.db.sql("SELECT summary_params::text FROM exam_revisions WHERE exam_id = :e AND action = 'PUBLISHED'")
                .bind("e", exam.getId()).map(row -> row.get(0, String.class)).one().block();
        assertThat(JSON.readTree(params).get("versionNo").asInt()).isEqualTo(1);
        assertThat(JSON.readTree(params).get("sha256").asText()).isEqualTo(first.sha256());

        // Back to SCHEDULED, a changed text, published again: version 2 with another hash; version 1 stays as it was
        RescheduleExamRequest later = new RescheduleExamRequest();
        later.setStartTime(LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MINUTES));
        later.setEndTime(LocalDateTime.now().plusDays(2).truncatedTo(ChronoUnit.MINUTES));
        service.rescheduleExam(exam.getId(), later, ExamHistoryTest.nurlan()).block();
        stack.cache.invalidateExamCache(exam.getId()).block();
        var change = ExamHistoryTest.unchanged(service.getExamById(exam.getId(), OWNER, "INSTRUCTOR").block());
        change.getBlocks().get(0).getQuestions().get(1).setQuestionText("İkinci, dəyişdi");
        service.updateExam(exam.getId(), change, ExamHistoryTest.nurlan()).block();
        service.updateExamStatus(exam.getId(), ExamStatus.ACTIVE, ExamHistoryTest.nurlan()).block();

        List<ExamSnapshotRepository.Snapshot> versions = snapshots.list(exam.getId()).collectList().block();
        assertThat(versions).extracting(ExamSnapshotRepository.Snapshot::versionNo).containsExactly(2, 1);
        assertThat(versions.get(0).sha256()).isNotEqualTo(versions.get(1).sha256());
        assertThat(snapshots.find(exam.getId(), 1).block().sha256()).isEqualTo(first.sha256());
        assertThat(ExamSnapshotService.verify(snapshots.find(exam.getId(), 2).block())).isTrue();

        // A tampered copy would not match its hash
        var tampered = new ExamSnapshotRepository.Snapshot(first.examId(), 1,
                first.snapshotJson().replace("İkinci", "Başqa"), first.sha256(), first.actorUserId(), null, null, null);
        assertThat(ExamSnapshotService.verify(tampered)).isFalse();
    }
}
