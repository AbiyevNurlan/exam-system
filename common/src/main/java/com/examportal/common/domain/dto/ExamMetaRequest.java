package com.examportal.common.domain.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * PATCH /api/exams/{id}/meta: the fields of an exam that do not decide any result, changeable also
 * when the exam has attempts. A field that is left out stays as it is; an empty description removes
 * it. Any other field (questions, points, duration, times ...) is refused with 400, never ignored.
 * Title rules as on create: 3-255 characters, not blank.
 */
@Getter
@NoArgsConstructor
public class ExamMetaRequest {

    @Size(min = 3, max = 255, message = "Title must be between 3 and 255 characters")
    private String title;
    private boolean titlePresent;

    @Size(max = 5000, message = "Description cannot exceed 5000 characters")
    private String description;
    private boolean descriptionPresent;

    private final List<String> otherFields = new ArrayList<>();

    public void setTitle(String title) {
        this.title = title;
        this.titlePresent = true;
    }

    public void setDescription(String description) {
        this.description = description;
        this.descriptionPresent = true;
    }

    @JsonAnySetter
    public void otherField(String name, Object ignored) {
        otherFields.add(name);
    }
}
