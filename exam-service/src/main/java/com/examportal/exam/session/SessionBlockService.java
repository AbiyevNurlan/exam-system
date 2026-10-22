package com.examportal.exam.session;

import com.examportal.common.domain.dto.QuestionResponse;
import com.examportal.common.domain.dto.SessionBlocksResponse;
import com.examportal.common.domain.entity.Exam;
import com.examportal.common.domain.entity.ExamBlock;
import com.examportal.common.domain.entity.ExamSession;
import com.examportal.common.domain.entity.TimingMode;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.repository.ExamBlockRepository;
import com.examportal.exam.session.BlockTimeline.BlockSpec;
import com.examportal.exam.session.BlockTimeline.Config;
import com.examportal.exam.session.BlockTimeline.Phase;
import com.examportal.exam.session.BlockTimeline.Progress;
import com.examportal.exam.session.BlockTimeline.State;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * The blocks of a session (B12b): where it is (BlockTimeline), stored in session_block_progress (exam V23).
 *
 * Every step runs in one transaction that first locks the session row, so two requests of the same session take
 * turns and never write different timelines. The session's end_time follows the timeline (it only gets earlier:
 * a block or a break left early), so the scheduler completes the session when its last block really ends and an
 * answer after that is refused by the upsert itself.
 *
 * Only exams with block rules take part: more than one block, block timers, or a block without free navigation.
 * An exam with one block, the exam timer and free navigation (every exam before B12a) is untouched: no rows, no
 * checks, the same behaviour as before.
 */
@Service
@RequiredArgsConstructor
public class SessionBlockService {

    // The blocks of an exam with sessions do not change (B9), so they may be kept for a little while
    private static final Duration LAYOUT_TTL = Duration.ofSeconds(30);

    private final DatabaseClient db;
    private final ExamBlockRepository blockRepository;
    private final TransactionalOperator tx;

    private final Map<Long, CachedLayout> layouts = new ConcurrentHashMap<>();

    private record CachedLayout(Optional<Layout> layout, Instant loadedAt) {
    }

    /**
     * The blocks of an exam with the ids of their questions in the stored order
     */
    public record Layout(Exam exam, List<ExamBlock> blocks, Map<Long, List<Long>> questionIds) {

        public boolean shuffles(ExamBlock block) {
            // One block: the exam's own setting, as before B12a; several: each block's
            return blocks.size() > 1 ? Boolean.TRUE.equals(block.getShuffleQuestions())
                    : Boolean.TRUE.equals(exam.getShuffleQuestions());
        }

        public ExamBlock blockOf(Long questionId) {
            for (ExamBlock block : blocks) {
                if (questionIds.getOrDefault(block.getId(), List.of()).contains(questionId)) {
                    return block;
                }
            }
            return null;
        }

        /**
         * The questions of a block in the order this session sees them
         */
        public List<Long> order(ExamBlock block, String sessionId) {
            return SessionOrder.of(questionIds.getOrDefault(block.getId(), List.of()), shuffles(block), sessionId, block.getId());
        }
    }

    /**
     * Where a session is now, with what it is computed from
     */
    public record Snapshot(Layout layout, Config config, State state) {

        public Phase phase() {
            return state.phase();
        }

        /**
         * The blocks whose questions the participant may see and answer now
         */
        public Set<Long> openBlockIds() {
            if (state.phase() != Phase.BLOCK) {
                return Set.of();
            }
            if (!config.sequential()) {
                return layout.blocks().stream().map(ExamBlock::getId).collect(Collectors.toSet());
            }
            return layout.blocks().stream().filter(block -> block.getPosition() == state.current())
                    .map(ExamBlock::getId).collect(Collectors.toSet());
        }

        Progress row(int position) {
            return state.rows().stream().filter(row -> row.position() == position).findFirst().orElse(null);
        }
    }

    /**
     * Whether sessions of this exam follow block rules: more than one block, block timers, or a block that goes
     * forward only
     */
    public static boolean usesRules(Exam exam, List<ExamBlock> blocks) {
        return blocks.size() > 1
                || exam.getTimingMode() == TimingMode.BLOCK_TIMERS
                || blocks.stream().anyMatch(block -> Boolean.FALSE.equals(block.getFreeNavigation()));
    }

    /**
     * The layout of an exam with block rules; empty for an exam without them
     */
    public Mono<Optional<Layout>> layout(Exam exam) {
        CachedLayout cached = layouts.get(exam.getId());
        if (cached != null && cached.loadedAt().plus(LAYOUT_TTL).isAfter(Instant.now())) {
            return Mono.just(cached.layout());
        }
        return blockRepository.findByExamIdOrderByPosition(exam.getId()).collectList()
                .flatMap(blocks -> {
                    if (blocks.isEmpty() || !usesRules(exam, blocks)) {
                        return Mono.just(Optional.<Layout>empty());
                    }
                    return db.sql("""
                                    SELECT id, block_id FROM questions WHERE exam_id = :examId AND active = true
                                    ORDER BY order_index, id
                                    """)
                            .bind("examId", exam.getId())
                            .map(row -> new Long[]{row.get("id", Long.class), row.get("block_id", Long.class)})
                            .all()
                            .collectList()
                            .map(rows -> {
                                Map<Long, List<Long>> byBlock = new LinkedHashMap<>();
                                rows.forEach(row -> byBlock.computeIfAbsent(row[1], key -> new ArrayList<>()).add(row[0]));
                                return Optional.of(new Layout(exam, blocks, byBlock));
                            });
                })
                .doOnNext(layout -> layouts.put(exam.getId(), new CachedLayout(layout, Instant.now())));
    }

    public Config config(ExamSession session, Layout layout) {
        Exam exam = layout.exam();
        LocalDateTime hardEnd = session.getStartTime().plusMinutes(exam.getDurationMinutes() == null ? 0 : exam.getDurationMinutes());
        if (exam.getEndTime() != null && exam.getEndTime().isBefore(hardEnd)) {
            hardEnd = exam.getEndTime();
        }
        boolean blockTimers = exam.getTimingMode() == TimingMode.BLOCK_TIMERS;
        List<BlockSpec> specs = layout.blocks().stream()
                .map(block -> new BlockSpec(block.getId(), block.getPosition(), block.getTitle(), block.getInstructions(),
                        block.getTimeLimitMinutes(), block.getBreakAfterMinutes() == null ? 0 : block.getBreakAfterMinutes(),
                        !Boolean.FALSE.equals(block.getFreeNavigation())))
                .toList();
        boolean sequential = blockTimers || !Boolean.TRUE.equals(exam.getAllowReturnToPreviousBlocks());
        return new Config(sequential, blockTimers, !Boolean.FALSE.equals(exam.getAllowSkipBreak()), specs,
                session.getStartTime(), hardEnd);
    }

    /**
     * Where the session is now; what the time has moved on is stored
     */
    public Mono<Snapshot> current(ExamSession session, Layout layout) {
        return step(session, layout, (config, rows) -> BlockTimeline.advance(config, rows, LocalDateTime.now()));
    }

    /**
     * The participant leaves the current block; 409 BLOCK_NOT_FINISHABLE when no block is being taken one at a time
     */
    public Mono<Snapshot> finishBlock(ExamSession session, Layout layout) {
        return step(session, layout, (config, rows) -> {
            LocalDateTime now = LocalDateTime.now();
            State before = BlockTimeline.advance(config, rows, now);
            if (!config.sequential() || before.phase() != Phase.BLOCK) {
                throw ExamException.blockNotFinishable();
            }
            return BlockTimeline.finishCurrent(config, rows, now);
        });
    }

    /**
     * The participant ends the break; 409 BREAK_NOT_SKIPPABLE when the exam does not allow it, SESSION_ON_BREAK
     * is not the answer then: the break simply goes on
     */
    public Mono<Snapshot> endBreak(ExamSession session, Layout layout) {
        return step(session, layout, (config, rows) -> {
            LocalDateTime now = LocalDateTime.now();
            State before = BlockTimeline.advance(config, rows, now);
            if (before.phase() != Phase.BREAK) {
                return before;
            }
            if (!config.skipBreakAllowed()) {
                throw ExamException.breakNotSkippable();
            }
            return BlockTimeline.endBreak(config, rows, now);
        });
    }

    /**
     * Whether this question may be answered now, from a fresh snapshot. A block that goes forward only also moves
     * its furthest question to this one. Errors: 409 SESSION_ON_BREAK, BLOCK_CLOSED, QUESTION_LOCKED; DONE is for the
     * caller (the session is over: it completes it).
     */
    public Mono<Snapshot> admitAnswer(ExamSession session, Layout layout, Long questionId) {
        return current(session, layout).flatMap(snapshot -> admit(session, snapshot, questionId));
    }

    /**
     * A block that goes forward only: the participant moved on to this question, the ones before it close
     */
    public Mono<Snapshot> reach(ExamSession session, Layout layout, Long questionId) {
        return admitAnswer(session, layout, questionId);
    }

    private Mono<Snapshot> admit(ExamSession session, Snapshot snapshot, Long questionId) {
        if (snapshot.phase() == Phase.DONE) {
            return Mono.just(snapshot);
        }
        if (snapshot.phase() == Phase.BREAK) {
            return Mono.error(ExamException.onBreak());
        }
        ExamBlock block = snapshot.layout().blockOf(questionId);
        if (block == null || !snapshot.openBlockIds().contains(block.getId())) {
            return Mono.error(ExamException.blockClosed());
        }
        if (!Boolean.FALSE.equals(block.getFreeNavigation())) {
            return Mono.just(snapshot);
        }
        int index = snapshot.layout().order(block, session.getSessionId()).indexOf(questionId);
        Progress row = snapshot.row(block.getPosition());
        int cursor = row == null ? 0 : row.cursor();
        if (index < cursor) {
            return Mono.error(ExamException.questionLocked());
        }
        if (index == cursor) {
            return Mono.just(snapshot);
        }
        return db.sql("""
                        UPDATE session_block_progress SET cursor = GREATEST(cursor, :cursor)
                        WHERE session_id = :sessionId AND position = :position
                        """)
                .bind("cursor", index)
                .bind("sessionId", session.getId())
                .bind("position", block.getPosition())
                .fetch().rowsUpdated()
                .thenReturn(withCursor(snapshot, block.getPosition(), index));
    }

    private static Snapshot withCursor(Snapshot snapshot, int position, int cursor) {
        List<Progress> rows = snapshot.state().rows().stream()
                .map(row -> row.position() == position ? row.withCursor(Math.max(row.cursor(), cursor)) : row)
                .toList();
        State state = new State(snapshot.state().phase(), snapshot.state().current(), rows, snapshot.state().projectedEnd());
        return new Snapshot(snapshot.layout(), snapshot.config(), state);
    }

    /**
     * One step of the timeline under the lock of the session row: read what is stored, compute, write what changed
     */
    private Mono<Snapshot> step(ExamSession session, Layout layout, BiFunction<Config, List<Progress>, State> compute) {
        Config config = config(session, layout);
        Mono<Snapshot> work = db.sql("SELECT id FROM exam_sessions WHERE id = :id FOR UPDATE")
                .bind("id", session.getId())
                .map(row -> 1)
                .first()
                .then(Mono.defer(() -> stored(session.getId())))
                .flatMap(stored -> {
                    State state = compute.apply(config, stored);
                    return write(session, stored, state).thenReturn(new Snapshot(layout, config, state));
                });
        return tx.transactional(work);
    }

    private Mono<List<Progress>> stored(Long sessionDbId) {
        return db.sql("""
                        SELECT position, block_id, started_at, deadline, finished_at, break_until, cursor
                        FROM session_block_progress WHERE session_id = :sessionId ORDER BY position
                        """)
                .bind("sessionId", sessionDbId)
                .map(row -> new Progress(row.get("position", Integer.class), row.get("block_id", Long.class),
                        row.get("started_at", LocalDateTime.class), row.get("deadline", LocalDateTime.class),
                        row.get("finished_at", LocalDateTime.class), row.get("break_until", LocalDateTime.class),
                        row.get("cursor", Integer.class)))
                .all()
                .collectList();
    }

    private Mono<Void> write(ExamSession session, List<Progress> stored, State state) {
        Map<Integer, Progress> before = stored.stream().collect(Collectors.toMap(Progress::position, row -> row));
        List<Mono<Long>> writes = new ArrayList<>();
        for (Progress row : state.rows()) {
            if (Objects.equals(before.get(row.position()), row)) {
                continue;
            }
            DatabaseClient.GenericExecuteSpec spec = db.sql("""
                            INSERT INTO session_block_progress (session_id, position, block_id, started_at, deadline,
                                                                finished_at, break_until, cursor)
                            VALUES (:sessionId, :position, :blockId, :startedAt, :deadline, :finishedAt, :breakUntil, :cursor)
                            ON CONFLICT (session_id, position) DO UPDATE
                            SET finished_at = EXCLUDED.finished_at, break_until = EXCLUDED.break_until,
                                cursor = GREATEST(session_block_progress.cursor, EXCLUDED.cursor)
                            """)
                    .bind("sessionId", session.getId())
                    .bind("position", row.position())
                    .bind("blockId", row.blockId())
                    .bind("startedAt", row.startedAt())
                    .bind("deadline", row.deadline())
                    .bind("cursor", row.cursor());
            spec = row.finishedAt() == null ? spec.bindNull("finishedAt", LocalDateTime.class) : spec.bind("finishedAt", row.finishedAt());
            spec = row.breakUntil() == null ? spec.bindNull("breakUntil", LocalDateTime.class) : spec.bind("breakUntil", row.breakUntil());
            writes.add(spec.fetch().rowsUpdated());
        }
        // The end of the session follows the timeline; it only ever gets earlier
        Mono<Long> end = db.sql("""
                        UPDATE exam_sessions SET end_time = :endTime, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND status = 'IN_PROGRESS' AND (end_time IS NULL OR end_time > :endTime)
                        """)
                .bind("endTime", state.projectedEnd())
                .bind("id", session.getId())
                .fetch().rowsUpdated();
        return reactor.core.publisher.Flux.concat(writes).then(end).then();
    }

    /**
     * What the participant's screen needs: the blocks with their state, the times, and the questions that may be
     * seen now (from the session's own list, in its order)
     */
    public SessionBlocksResponse response(ExamSession session, Snapshot snapshot, List<QuestionResponse> sessionQuestions) {
        State state = snapshot.state();
        Config config = snapshot.config();
        Set<Long> open = snapshot.openBlockIds();
        List<SessionBlocksResponse.Block> blocks = new ArrayList<>();
        for (ExamBlock block : snapshot.layout().blocks()) {
            Progress row = snapshot.row(block.getPosition());
            String status;
            if (!config.sequential()) {
                status = "OPEN";
            } else if (row == null) {
                status = "UPCOMING";
            } else if (row.finishedAt() != null) {
                status = "DONE";
            } else {
                status = "CURRENT";
            }
            blocks.add(SessionBlocksResponse.Block.builder()
                    .blockId(block.getId())
                    .position(block.getPosition())
                    .title(block.getTitle())
                    .instructions(block.getInstructions())
                    .questionCount(snapshot.layout().questionIds().getOrDefault(block.getId(), List.of()).size())
                    .timeLimitMinutes(config.blockTimers() ? block.getTimeLimitMinutes() : null)
                    .breakAfterMinutes(config.blockTimers() ? block.getBreakAfterMinutes() : null)
                    .freeNavigation(!Boolean.FALSE.equals(block.getFreeNavigation()))
                    .status(status)
                    .startedAt(row != null && config.sequential() ? row.startedAt() : null)
                    .deadline(row != null && config.sequential() ? row.deadline() : null)
                    .finishedAt(row != null ? row.finishedAt() : null)
                    .cursor(row != null && Boolean.FALSE.equals(block.getFreeNavigation()) ? row.cursor() : null)
                    .build());
        }
        Progress current = snapshot.row(state.current());
        boolean onBreak = state.phase() == Phase.BREAK;
        // The ones that may be seen now, in the session's order (blocks by position, the block's own order inside)
        Map<Long, Integer> blockPosition = snapshot.layout().blocks().stream()
                .collect(Collectors.toMap(ExamBlock::getId, ExamBlock::getPosition));
        List<QuestionResponse> questions = sessionQuestions.stream()
                .filter(question -> question.getBlockId() != null && open.contains(question.getBlockId()))
                .sorted(java.util.Comparator.comparing(question -> blockPosition.getOrDefault(question.getBlockId(), 0)))
                .toList();
        return SessionBlocksResponse.builder()
                .sessionId(session.getSessionId())
                .phase(state.phase().name())
                .sequential(config.sequential())
                .timingMode(config.blockTimers() ? TimingMode.BLOCK_TIMERS.name() : TimingMode.EXAM_TIMER.name())
                .current(state.current())
                .serverTime(LocalDateTime.now())
                .sessionEndTime(state.projectedEnd())
                .blockDeadline(state.phase() == Phase.BLOCK && current != null && config.sequential() ? current.deadline() : null)
                .breakUntil(onBreak && current != null ? current.breakUntil() : null)
                .canSkipBreak(onBreak ? config.skipBreakAllowed() : null)
                .cursor(state.phase() == Phase.BLOCK && current != null && config.sequential() ? current.cursor() : null)
                .blocks(blocks)
                .questions(questions)
                .build();
    }
}
