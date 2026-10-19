package com.examportal.exam.structure;

import com.examportal.common.domain.dto.ExamBlockDTO;
import com.examportal.common.domain.dto.OptionDTO;
import com.examportal.common.domain.dto.QuestionDTO;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.structure.ExamState.BlockState;
import com.examportal.exam.structure.ExamState.OptionState;
import com.examportal.exam.structure.ExamState.QuestionState;
import com.examportal.exam.structure.StructurePlan.BlockPlan;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the blocks or questions of a create or update request into a StructurePlan (B12a).
 *
 * A request without blocks (the format before B12a) is one block: on create the block the database made
 * with the exam ("Əsas blok"), on update the exam's only block with its settings unchanged; an exam with
 * more than one block needs blocks (400 BLOCKS_REQUIRED) so that no block is dropped silently.
 *
 * Ids: an id that belongs to this exam (a block of it, a question of it, an option of that question)
 * keeps its row; an unknown id, or one given a second time, is treated as new, the way ids were ignored
 * before B12a. A create request never reuses an id.
 */
@Component
public class StructurePlanner {

    public static final String MAIN_BLOCK_TITLE = "Əsas blok";

    /**
     * A new exam. mainBlockId: the block the database created with the exam, taken by the first block
     */
    public StructurePlan forCreate(List<ExamBlockDTO> blocks, List<QuestionDTO> questions, Long mainBlockId, boolean shuffle) {
        if (blocks == null || blocks.isEmpty()) {
            questions.forEach(StructurePlanner::forgetIds);
            return new StructurePlan(List.of(new BlockPlan(mainBlockId, MAIN_BLOCK_TITLE, null, null, 0, true, shuffle, true,
                    questions)));
        }
        List<BlockPlan> plan = new java.util.ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            ExamBlockDTO block = blocks.get(i);
            List<QuestionDTO> blockQuestions = block.getQuestions() == null ? List.of() : block.getQuestions();
            blockQuestions.forEach(StructurePlanner::forgetIds);
            plan.add(toPlan(i == 0 ? mainBlockId : null, block, blockQuestions, i == blocks.size() - 1, shuffle));
        }
        return new StructurePlan(plan);
    }

    /**
     * A change of a stored exam
     */
    public StructurePlan forUpdate(List<ExamBlockDTO> blocks, List<QuestionDTO> questions, ExamState current,
                                   boolean shuffle) {
        Map<Long, QuestionState> storedQuestions = new HashMap<>();
        current.questions().forEach(question -> storedQuestions.put(question.id(), question));
        Set<Long> seenQuestions = new HashSet<>();

        if (blocks == null || blocks.isEmpty()) {
            if (current.blocks().size() > 1) {
                throw ExamException.blocksRequired();
            }
            BlockState only = current.blocks().isEmpty() ? null : current.blocks().get(0);
            questions.forEach(question -> resolveIds(question, storedQuestions, seenQuestions));
            if (only == null) {
                return new StructurePlan(List.of(new BlockPlan(null, MAIN_BLOCK_TITLE, null, null, 0, true, shuffle, true,
                        questions)));
            }
            return new StructurePlan(List.of(new BlockPlan(only.id(), only.title(), only.instructions(),
                    only.timeLimitMinutes(), only.breakAfterMinutes(), only.freeNavigation(), only.shuffleQuestions(),
                    only.showResultToParticipant(), questions)));
        }

        Set<Long> storedBlocks = new HashSet<>();
        current.blocks().forEach(block -> storedBlocks.add(block.id()));
        Set<Long> seenBlocks = new HashSet<>();
        List<BlockPlan> plan = new java.util.ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            ExamBlockDTO block = blocks.get(i);
            Long id = block.getId() != null && storedBlocks.contains(block.getId()) && seenBlocks.add(block.getId())
                    ? block.getId() : null;
            List<QuestionDTO> blockQuestions = block.getQuestions() == null ? List.of() : block.getQuestions();
            blockQuestions.forEach(question -> resolveIds(question, storedQuestions, seenQuestions));
            plan.add(toPlan(id, block, blockQuestions, i == blocks.size() - 1, shuffle));
        }
        return new StructurePlan(plan);
    }

    private static BlockPlan toPlan(Long id, ExamBlockDTO block, List<QuestionDTO> questions, boolean last, boolean shuffle) {
        String title = block.getTitle() == null || block.getTitle().isBlank() ? MAIN_BLOCK_TITLE : block.getTitle().trim();
        String instructions = block.getInstructions() == null || block.getInstructions().isBlank() ? null : block.getInstructions();
        // Nothing comes after the last block, so it has no break
        int breakAfter = last || block.getBreakAfterMinutes() == null ? 0 : block.getBreakAfterMinutes();
        return new BlockPlan(id, title, instructions, block.getTimeLimitMinutes(), breakAfter,
                block.getFreeNavigation() == null || block.getFreeNavigation(),
                block.getShuffleQuestions() != null ? block.getShuffleQuestions() : shuffle,
                block.getShowResultToParticipant() == null || block.getShowResultToParticipant(),
                questions);
    }

    private static void resolveIds(QuestionDTO question, Map<Long, QuestionState> stored, Set<Long> seen) {
        QuestionState existing = question.getId() != null && seen.add(question.getId()) ? stored.get(question.getId()) : null;
        if (existing == null) {
            forgetIds(question);
            return;
        }
        Set<Long> storedOptions = new HashSet<>();
        existing.options().stream().map(OptionState::id).forEach(storedOptions::add);
        Set<Long> seenOptions = new HashSet<>();
        if (question.getOptions() != null) {
            for (OptionDTO option : question.getOptions()) {
                if (option.getId() != null && !(storedOptions.contains(option.getId()) && seenOptions.add(option.getId()))) {
                    option.setId(null);
                }
            }
        }
    }

    private static void forgetIds(QuestionDTO question) {
        question.setId(null);
        if (question.getOptions() != null) {
            question.getOptions().forEach(option -> option.setId(null));
        }
    }
}
