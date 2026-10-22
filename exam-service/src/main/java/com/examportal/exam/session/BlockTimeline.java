package com.examportal.exam.session;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The rules of the blocks of one session (B12b), without any storage: given the blocks of the exam, what was
 * stored about the session so far and the time now, where the session is.
 *
 * Sequential (block timers, or the exam timer without returning to earlier blocks): one block at a time, in order.
 * A block ends when the participant leaves it or when its time runs out (its own limit with block timers, the end
 * of the session otherwise). With block timers a break may follow; the next block starts when the break ends or
 * when the participant ends it early (if the exam allows that). A block that was left is closed for good.
 *
 * Open (the exam timer and returning allowed): every block is open from the start until the session ends.
 *
 * The timeline is the same whether or not the participant is looking: a block whose time ran out while nobody
 * asked is finished at its deadline, and the next block starts when the break would have ended. So the stored
 * rows only need the moments the participant chose (leaving a block early, ending a break early); everything else
 * follows from the times.
 */
public final class BlockTimeline {

    private BlockTimeline() {
    }

    public enum Phase { BLOCK, BREAK, DONE }

    /**
     * A block of the exam as the session takes it
     */
    public record BlockSpec(Long blockId, int position, String title, String instructions, Integer timeLimitMinutes,
                            int breakAfterMinutes, boolean freeNavigation) {
    }

    /**
     * What is stored about one block of a session (session_block_progress)
     */
    public record Progress(int position, Long blockId, LocalDateTime startedAt, LocalDateTime deadline,
                           LocalDateTime finishedAt, LocalDateTime breakUntil, int cursor) {

        Progress finished(LocalDateTime at, LocalDateTime breakEnd) {
            return new Progress(position, blockId, startedAt, deadline, at, breakEnd, cursor);
        }

        Progress breakEndedAt(LocalDateTime at) {
            return new Progress(position, blockId, startedAt, deadline, finishedAt, at, cursor);
        }

        public Progress withCursor(int next) {
            return new Progress(position, blockId, startedAt, deadline, finishedAt, breakUntil, next);
        }
    }

    /**
     * sessionStart: when the session began; hardEnd: the latest end of the session (its duration, cut by the end of
     * the exam window), which no block may pass
     */
    public record Config(boolean sequential, boolean blockTimers, boolean skipBreakAllowed, List<BlockSpec> blocks,
                         LocalDateTime sessionStart, LocalDateTime hardEnd) {
    }

    /**
     * current: the position of the block being taken, or of the block before the break; rows: every block entered
     * so far; projectedEnd: when the session ends if the participant leaves nothing early from now on
     */
    public record State(Phase phase, int current, List<Progress> rows, LocalDateTime projectedEnd) {

        public Progress currentRow() {
            return rows.stream().filter(row -> row.position() == current).findFirst().orElse(null);
        }
    }

    /**
     * Where the session is now
     */
    public static State advance(Config config, List<Progress> stored, LocalDateTime now) {
        List<Progress> rows = new ArrayList<>(stored);
        rows.sort(java.util.Comparator.comparingInt(Progress::position));
        if (!config.sequential()) {
            return open(config, rows, now);
        }
        if (rows.isEmpty()) {
            rows.add(start(config, 0, config.sessionStart()));
        }
        while (true) {
            int last = rows.size() - 1;
            Progress current = rows.get(last);
            if (current.finishedAt() == null) {
                if (now.isBefore(current.deadline())) {
                    return state(config, Phase.BLOCK, current.position(), rows);
                }
                current = current.finished(current.deadline(), breakAfter(config, last, current.deadline()));
                rows.set(last, current);
            }
            if (last == config.blocks().size() - 1) {
                return state(config, Phase.DONE, current.position(), rows);
            }
            LocalDateTime nextStart = current.breakUntil() != null ? current.breakUntil() : current.finishedAt();
            if (now.isBefore(nextStart)) {
                return state(config, Phase.BREAK, current.position(), rows);
            }
            rows.add(start(config, last + 1, nextStart));
        }
    }

    /**
     * The participant leaves the current block before its time is up. Only while a block is being taken in a
     * sequential session; the state stays as it is otherwise (the caller tells the participant why).
     */
    public static State finishCurrent(Config config, List<Progress> stored, LocalDateTime now) {
        State state = advance(config, stored, now);
        if (!config.sequential() || state.phase() != Phase.BLOCK) {
            return state;
        }
        List<Progress> rows = new ArrayList<>(state.rows());
        int index = rows.size() - 1;
        rows.set(index, rows.get(index).finished(now, breakAfter(config, index, now)));
        return advance(config, rows, now);
    }

    /**
     * The participant ends the break early (when the exam allows it); the next block starts now
     */
    public static State endBreak(Config config, List<Progress> stored, LocalDateTime now) {
        State state = advance(config, stored, now);
        if (state.phase() != Phase.BREAK || !config.skipBreakAllowed()) {
            return state;
        }
        List<Progress> rows = new ArrayList<>(state.rows());
        int index = rows.size() - 1;
        rows.set(index, rows.get(index).breakEndedAt(now));
        return advance(config, rows, now);
    }

    private static State open(Config config, List<Progress> rows, LocalDateTime now) {
        // Every block is entered at the start and stays open until the session ends
        for (int i = rows.size(); i < config.blocks().size(); i++) {
            BlockSpec block = config.blocks().get(i);
            rows.add(new Progress(block.position(), block.blockId(), config.sessionStart(), config.hardEnd(), null, null, 0));
        }
        Phase phase = now.isBefore(config.hardEnd()) ? Phase.BLOCK : Phase.DONE;
        return new State(phase, config.blocks().get(0).position(), rows, config.hardEnd());
    }

    private static Progress start(Config config, int index, LocalDateTime at) {
        BlockSpec block = config.blocks().get(index);
        LocalDateTime deadline = config.hardEnd();
        if (config.blockTimers() && block.timeLimitMinutes() != null) {
            deadline = min(at.plusMinutes(block.timeLimitMinutes()), config.hardEnd());
        }
        // A block that would start after the end of the session is over the moment it starts
        if (deadline.isBefore(at)) {
            deadline = at;
        }
        return new Progress(block.position(), block.blockId(), at, deadline, null, null, 0);
    }

    // The end of the break after the block at this index, or null: only with block timers, never after the last block
    private static LocalDateTime breakAfter(Config config, int index, LocalDateTime finished) {
        BlockSpec block = config.blocks().get(index);
        if (!config.blockTimers() || index == config.blocks().size() - 1 || block.breakAfterMinutes() <= 0) {
            return null;
        }
        return min(finished.plusMinutes(block.breakAfterMinutes()), config.hardEnd());
    }

    private static State state(Config config, Phase phase, int current, List<Progress> rows) {
        return new State(phase, current, List.copyOf(rows), projectedEnd(config, phase, rows));
    }

    /**
     * When the session ends if nothing is left early from now on: the end of what is running now, then every later
     * break and block in full; never after the hard end
     */
    static LocalDateTime projectedEnd(Config config, Phase phase, List<Progress> rows) {
        Progress last = rows.get(rows.size() - 1);
        if (phase == Phase.DONE) {
            return min(last.finishedAt() != null ? last.finishedAt() : last.deadline(), config.hardEnd());
        }
        if (!config.blockTimers()) {
            return config.hardEnd();
        }
        int index = rows.size() - 1;
        LocalDateTime end = phase == Phase.BLOCK ? last.deadline() : last.breakUntil() != null ? last.breakUntil() : last.finishedAt();
        for (int i = index + 1; i < config.blocks().size(); i++) {
            BlockSpec block = config.blocks().get(i);
            if (i > index + 1 || phase == Phase.BLOCK) {
                // The break before block i (after block i - 1); the one running now is already in end
                BlockSpec before = config.blocks().get(i - 1);
                end = end.plusMinutes(before.breakAfterMinutes());
            }
            end = end.plusMinutes(block.timeLimitMinutes() == null ? 0 : block.timeLimitMinutes());
        }
        return min(end, config.hardEnd());
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }
}
