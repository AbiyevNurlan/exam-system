package com.examportal.common.domain.profile;

import com.examportal.common.domain.dto.profile.ParticipantProfileDtos;
import com.examportal.common.domain.dto.profile.ParticipantProfileDtos.UpdateRequest;
import com.examportal.common.exception.ValidationException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The rules of a participant profile, in one place for whoever stores it.
 * A request is normalized (category upper case, text cleaned by ProfileText, empty = null) and checked:
 * the category must be one of the active categories, the fields that participant-profile.required-fields
 * lists for it must be filled, and a birth date must lie between 1900-01-01 and today.
 */
public final class ParticipantProfileRules {

    private static final LocalDate EARLIEST_BIRTH_DATE = LocalDate.of(1900, 1, 1);

    private ParticipantProfileRules() {
    }

    public static UpdateRequest normalize(UpdateRequest request, Collection<String> activeCategories,
                                          Map<String, List<String>> requiredFields, LocalDate today) {
        if (request == null) {
            throw new ValidationException("category", "is required");
        }
        String category = request.getCategory() == null ? null : request.getCategory().trim().toUpperCase(Locale.ROOT);
        if (category == null || category.isEmpty()) {
            throw new ValidationException("category", "is required");
        }
        if (category.length() > ParticipantProfileDtos.MAX_CATEGORY || !activeCategories.contains(category)) {
            throw new ValidationException("category", "must be one of " + String.join(", ", activeCategories));
        }
        LocalDate birthDate = request.getBirthDate();
        if (birthDate != null && (birthDate.isBefore(EARLIEST_BIRTH_DATE) || birthDate.isAfter(today))) {
            throw new ValidationException("birthDate", "must be between 1900-01-01 and today");
        }

        UpdateRequest normalized = UpdateRequest.builder()
                .category(category)
                .institution(ProfileText.clean("institution", request.getInstitution(), ParticipantProfileDtos.MAX_INSTITUTION))
                .facultyOrDepartment(ProfileText.clean("facultyOrDepartment", request.getFacultyOrDepartment(),
                        ParticipantProfileDtos.MAX_FACULTY_OR_DEPARTMENT))
                .courseOrGrade(ProfileText.clean("courseOrGrade", request.getCourseOrGrade(), ParticipantProfileDtos.MAX_COURSE_OR_GRADE))
                .workplace(ProfileText.clean("workplace", request.getWorkplace(), ParticipantProfileDtos.MAX_WORKPLACE))
                .position(ProfileText.clean("position", request.getPosition(), ParticipantProfileDtos.MAX_POSITION))
                .city(ProfileText.clean("city", request.getCity(), ParticipantProfileDtos.MAX_CITY))
                .birthDate(birthDate)
                .build();

        List<String> required = requiredFields == null ? List.of() : requiredFields.getOrDefault(category, List.of());
        Map<String, Object> values = values(normalized);
        List<String> missing = new ArrayList<>();
        for (String field : required) {
            if (values.containsKey(field) && values.get(field) == null) {
                missing.add(field);
            }
        }
        if (!missing.isEmpty()) {
            throw new ValidationException(missing.get(0), "is required for the category " + category
                    + " (required: " + String.join(", ", required) + ")");
        }
        return normalized;
    }

    /**
     * "field: before -> after" for every field that differs, joined by "; ". Empty when nothing changed.
     * Text is quoted so that a change of white space or an empty value can be read.
     */
    public static String describeChanges(ParticipantProfileDtos.Profile before, UpdateRequest after) {
        Map<String, Object> old = before == null ? null : values(before);
        Map<String, Object> now = values(after);
        List<String> changes = new ArrayList<>();
        Object oldCategory = old == null ? null : old.get("category");
        if (!Objects.equals(oldCategory, now.get("category"))) {
            changes.add("category: " + show(oldCategory) + " -> " + show(now.get("category")));
        }
        for (String field : ParticipantProfileDtos.DETAIL_FIELDS) {
            Object was = old == null ? null : old.get(field);
            Object is = now.get(field);
            if (!Objects.equals(was, is)) {
                changes.add(field + ": " + show(was) + " -> " + show(is));
            }
        }
        return String.join("; ", changes);
    }

    private static String show(Object value) {
        if (value == null) {
            return "-";
        }
        return value instanceof String text ? "'" + text + "'" : value.toString();
    }

    public static Map<String, Object> values(UpdateRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("category", request.getCategory());
        values.put("institution", request.getInstitution());
        values.put("facultyOrDepartment", request.getFacultyOrDepartment());
        values.put("courseOrGrade", request.getCourseOrGrade());
        values.put("workplace", request.getWorkplace());
        values.put("position", request.getPosition());
        values.put("city", request.getCity());
        values.put("birthDate", request.getBirthDate());
        return values;
    }

    private static Map<String, Object> values(ParticipantProfileDtos.Profile profile) {
        return values(UpdateRequest.builder()
                .category(profile.getCategory())
                .institution(profile.getInstitution())
                .facultyOrDepartment(profile.getFacultyOrDepartment())
                .courseOrGrade(profile.getCourseOrGrade())
                .workplace(profile.getWorkplace())
                .position(profile.getPosition())
                .city(profile.getCity())
                .birthDate(profile.getBirthDate())
                .build());
    }
}
