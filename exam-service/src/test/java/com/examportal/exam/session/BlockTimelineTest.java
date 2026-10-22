package com.examportal.exam.session;

import com.examportal.exam.session.BlockTimeline.BlockSpec;
import com.examportal.exam.session.BlockTimeline.Config;
import com.examportal.exam.session.BlockTimeline.Phase;
import com.examportal.exam.session.BlockTimeline.Progress;
import com.examportal.exam.session.BlockTimeline.State;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B12b: the timeline of the blocks of one session, without storage. Three blocks of 30, 20 and 10 minutes with a
 * break of 5 after the first and 10 after the second; the session starts at 10:00.
 */
class BlockTimelineTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 22, 10, 0);

    private static final List<BlockSpec> BLOCKS = List.of(
            new BlockSpec(11L, 1, "Riyaziyyat", null, 30, 5, true),
            new BlockSpec(12L, 2, "Məntiq", null, 20, 10, false),
            new BlockSpec(13L, 3, "İngilis dili", null, 10, 0, true));

    // 30 + 5 + 20 + 10 + 10 = 75 minutes
    private static Config timers() {
        return new Config(true, true, true, BLOCKS, START, START.plusMinutes(75));
    }

    private static LocalDateTime at(int minutes) {
        return START.plusMinutes(minutes);
    }

    @Test
    void theFirstBlockStartsWithTheSession() {
        State state = BlockTimeline.advance(timers(), List.of(), at(1));

        assertThat(state.phase()).isEqualTo(Phase.BLOCK);
        assertThat(state.current()).isEqualTo(1);
        assertThat(state.rows()).hasSize(1);
        assertThat(state.currentRow().startedAt()).isEqualTo(START);
        assertThat(state.currentRow().deadline()).isEqualTo(at(30));
        assertThat(state.projectedEnd()).isEqualTo(at(75));
    }

    @Test
    void whenTheTimeOfABlockRunsOutTheBreakFollowsThenTheNextBlockOnItsOwn() {
        State onBreak = BlockTimeline.advance(timers(), List.of(), at(32));
        assertThat(onBreak.phase()).isEqualTo(Phase.BREAK);
        assertThat(onBreak.current()).isEqualTo(1);
        assertThat(onBreak.currentRow().finishedAt()).isEqualTo(at(30));
        assertThat(onBreak.currentRow().breakUntil()).isEqualTo(at(35));

        // Nobody looked for a while: the second block started when the break ended, at 10:35
        State second = BlockTimeline.advance(timers(), onBreak.rows(), at(40));
        assertThat(second.phase()).isEqualTo(Phase.BLOCK);
        assertThat(second.current()).isEqualTo(2);
        assertThat(second.currentRow().startedAt()).isEqualTo(at(35));
        assertThat(second.currentRow().deadline()).isEqualTo(at(55));
    }

    @Test
    void theWholeTimelineFollowsFromTheTimesAlone() {
        // Asked only once, long after: every block ran its full time and the session is over at 11:15
        State state = BlockTimeline.advance(timers(), List.of(), at(200));

        assertThat(state.phase()).isEqualTo(Phase.DONE);
        assertThat(state.rows()).extracting(Progress::startedAt).containsExactly(START, at(35), at(65));
        assertThat(state.rows()).extracting(Progress::finishedAt).containsExactly(at(30), at(55), at(75));
        assertThat(state.projectedEnd()).isEqualTo(at(75));
    }

    @Test
    void leavingABlockEarlyStartsTheBreakNowAndBringsTheEndForward() {
        State state = BlockTimeline.finishCurrent(timers(), List.of(), at(12));

        assertThat(state.phase()).isEqualTo(Phase.BREAK);
        assertThat(state.currentRow().finishedAt()).isEqualTo(at(12));
        assertThat(state.currentRow().breakUntil()).isEqualTo(at(17));
        // 17 + 20 + 10 + 10
        assertThat(state.projectedEnd()).isEqualTo(at(57));
    }

    @Test
    void endingABreakEarlyStartsTheNextBlockNow() {
        State onBreak = BlockTimeline.finishCurrent(timers(), List.of(), at(12));

        State next = BlockTimeline.endBreak(timers(), onBreak.rows(), at(13));

        assertThat(next.phase()).isEqualTo(Phase.BLOCK);
        assertThat(next.current()).isEqualTo(2);
        assertThat(next.currentRow().startedAt()).isEqualTo(at(13));
        assertThat(next.currentRow().deadline()).isEqualTo(at(33));
        assertThat(next.rows().get(0).breakUntil()).isEqualTo(at(13));
        assertThat(next.projectedEnd()).isEqualTo(at(53));
    }

    @Test
    void aBreakThatMayNotBeSkippedGoesOn() {
        Config strict = new Config(true, true, false, BLOCKS, START, START.plusMinutes(75));
        State onBreak = BlockTimeline.finishCurrent(strict, List.of(), at(12));

        State still = BlockTimeline.endBreak(strict, onBreak.rows(), at(13));

        assertThat(still.phase()).isEqualTo(Phase.BREAK);
        assertThat(still.currentRow().breakUntil()).isEqualTo(at(17));
    }

    @Test
    void leavingTheLastBlockEndsTheSession() {
        State state = BlockTimeline.advance(timers(), List.of(), at(66));
        assertThat(state.current()).isEqualTo(3);

        State done = BlockTimeline.finishCurrent(timers(), state.rows(), at(68));

        assertThat(done.phase()).isEqualTo(Phase.DONE);
        assertThat(done.projectedEnd()).isEqualTo(at(68));
    }

    @Test
    void theEndOfTheExamWindowCutsEveryBlock() {
        // The window closes at 10:50, in the middle of the second block
        Config cut = new Config(true, true, true, BLOCKS, START, at(50));

        State second = BlockTimeline.advance(cut, List.of(), at(40));
        assertThat(second.currentRow().deadline()).isEqualTo(at(50));
        assertThat(second.projectedEnd()).isEqualTo(at(50));

        State done = BlockTimeline.advance(cut, List.of(), at(51));
        assertThat(done.phase()).isEqualTo(Phase.DONE);
        assertThat(done.rows()).hasSize(3);
        assertThat(done.rows().get(2).startedAt()).isEqualTo(at(50));
        assertThat(done.rows().get(2).deadline()).isEqualTo(at(50));
    }

    @Test
    void underTheExamTimerBlocksHaveNoOwnLimitAndNoBreaks() {
        // Sequential, one timer of 60 minutes for everything
        Config examTimer = new Config(true, false, true, BLOCKS, START, at(60));

        State first = BlockTimeline.advance(examTimer, List.of(), at(45));
        assertThat(first.phase()).isEqualTo(Phase.BLOCK);
        assertThat(first.currentRow().deadline()).isEqualTo(at(60));

        State second = BlockTimeline.finishCurrent(examTimer, first.rows(), at(46));
        assertThat(second.phase()).isEqualTo(Phase.BLOCK);
        assertThat(second.current()).isEqualTo(2);
        assertThat(second.rows().get(0).breakUntil()).isNull();
        assertThat(second.projectedEnd()).isEqualTo(at(60));
    }

    @Test
    void anOpenSessionHasEveryBlockFromTheStart() {
        Config open = new Config(false, false, true, BLOCKS, START, at(60));

        State state = BlockTimeline.advance(open, List.of(), at(5));

        assertThat(state.phase()).isEqualTo(Phase.BLOCK);
        assertThat(state.rows()).hasSize(3).allSatisfy(row -> {
            assertThat(row.startedAt()).isEqualTo(START);
            assertThat(row.deadline()).isEqualTo(at(60));
            assertThat(row.finishedAt()).isNull();
        });
        // Nothing can be left in an open session
        assertThat(BlockTimeline.finishCurrent(open, state.rows(), at(6)).rows()).allSatisfy(row -> assertThat(row.finishedAt()).isNull());
        assertThat(BlockTimeline.advance(open, state.rows(), at(61)).phase()).isEqualTo(Phase.DONE);
    }

    @Test
    void theStoredRowsAreKeptAsTheyAre() {
        State first = BlockTimeline.finishCurrent(timers(), List.of(), at(12));
        List<Progress> withCursor = List.of(first.rows().get(0).withCursor(4));

        State again = BlockTimeline.advance(timers(), withCursor, at(14));

        assertThat(again.rows().get(0).cursor()).isEqualTo(4);
        assertThat(again.rows().get(0).finishedAt()).isEqualTo(at(12));
    }

    @Test
    void theSessionOrderIsTheSameEveryTimeAndKeepsEveryQuestion() {
        List<Long> stored = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);

        List<Long> once = SessionOrder.of(stored, true, "session-a", 11L);
        List<Long> twice = SessionOrder.of(stored, true, "session-a", 11L);

        assertThat(once).isEqualTo(twice).containsExactlyInAnyOrderElementsOf(stored);
        assertThat(SessionOrder.of(stored, false, "session-a", 11L)).isEqualTo(stored);
        // Another session gets its own order (with 8 questions the chance of the same one is 1 in 40 320)
        assertThat(SessionOrder.of(stored, true, "session-b", 11L)).isNotEqualTo(once);
    }
}
