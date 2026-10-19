package com.examportal.exam.structure;

import com.examportal.common.domain.dto.QuestionDTO;

import java.util.List;

/**
 * What the blocks and questions of an exam are to be after a save (B12a), in order. Ids are already
 * resolved against the stored state by StructurePlanner: a block, question or option id is set only
 * when that row exists in this exam (in that block, in that question); everything else is new.
 */
public record StructurePlan(List<BlockPlan> blocks) {

    public record BlockPlan(Long id, String title, String instructions, Integer timeLimitMinutes, int breakAfterMinutes,
                            boolean freeNavigation, boolean shuffleQuestions, boolean showResultToParticipant,
                            List<QuestionDTO> questions) {
    }

    public int questionCount() {
        return blocks.stream().mapToInt(block -> block.questions().size()).sum();
    }

    public List<QuestionDTO> questions() {
        return blocks.stream().flatMap(block -> block.questions().stream()).toList();
    }
}
