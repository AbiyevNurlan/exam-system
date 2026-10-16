package com.examportal.auth.repository;

import com.examportal.common.domain.dto.profile.ParticipantProfileDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Participant Profile Repository (participant_profiles, participant_categories; V4)
 * One row per user at most. Writes are one upsert on uq_participant_profiles_user, so two saves at once
 * end with one row and the later values.
 */
@Repository
@RequiredArgsConstructor
public class ParticipantProfileRepository {

    private static final String COLUMNS = "p.category, p.institution, p.faculty_or_department, p.course_or_grade, p.workplace, "
            + "p.position, p.city, p.birth_date, p.updated_at, (p.updated_by IS DISTINCT FROM p.user_id) AS updated_by_admin";

    private final DatabaseClient databaseClient;

    /**
     * The profile of the user; empty when there is none (every account from before B11)
     */
    public Mono<ParticipantProfileDtos.Profile> findByUserId(UUID userId) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM participant_profiles p WHERE p.user_id = :userId")
                .bind("userId", userId)
                .map(row -> ParticipantProfileDtos.Profile.builder()
                        .category(row.get("category", String.class))
                        .institution(row.get("institution", String.class))
                        .facultyOrDepartment(row.get("faculty_or_department", String.class))
                        .courseOrGrade(row.get("course_or_grade", String.class))
                        .workplace(row.get("workplace", String.class))
                        .position(row.get("position", String.class))
                        .city(row.get("city", String.class))
                        .birthDate(row.get("birth_date", LocalDate.class))
                        .updatedAt(row.get("updated_at", LocalDateTime.class))
                        .updatedByAdmin(Boolean.TRUE.equals(row.get("updated_by_admin", Boolean.class)))
                        .build())
                .one();
    }

    /**
     * The categories that may be chosen, in their order
     */
    public Mono<List<String>> activeCategories() {
        return databaseClient.sql("SELECT code FROM participant_categories WHERE active = true ORDER BY sort_order, code")
                .map(row -> row.get("code", String.class))
                .all()
                .collectList();
    }

    /**
     * Creates or replaces the profile of the user with the normalized values (ParticipantProfileRules)
     */
    public Mono<Void> upsert(UUID userId, ParticipantProfileDtos.UpdateRequest values, UUID updatedBy) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        INSERT INTO participant_profiles (user_id, category, institution, faculty_or_department, course_or_grade,
                                                          workplace, position, city, birth_date, updated_by)
                        VALUES (:userId, :category, :institution, :faculty, :course, :workplace, :position, :city, :birthDate, :updatedBy)
                        ON CONFLICT (user_id) DO UPDATE SET
                            category = EXCLUDED.category,
                            institution = EXCLUDED.institution,
                            faculty_or_department = EXCLUDED.faculty_or_department,
                            course_or_grade = EXCLUDED.course_or_grade,
                            workplace = EXCLUDED.workplace,
                            position = EXCLUDED.position,
                            city = EXCLUDED.city,
                            birth_date = EXCLUDED.birth_date,
                            updated_by = EXCLUDED.updated_by,
                            updated_at = CURRENT_TIMESTAMP,
                            version = participant_profiles.version + 1
                        """)
                .bind("userId", userId)
                .bind("category", values.getCategory())
                .bind("updatedBy", updatedBy);
        spec = text(spec, "institution", values.getInstitution());
        spec = text(spec, "faculty", values.getFacultyOrDepartment());
        spec = text(spec, "course", values.getCourseOrGrade());
        spec = text(spec, "workplace", values.getWorkplace());
        spec = text(spec, "position", values.getPosition());
        spec = text(spec, "city", values.getCity());
        spec = values.getBirthDate() == null ? spec.bindNull("birthDate", LocalDate.class) : spec.bind("birthDate", values.getBirthDate());
        return spec.then();
    }

    private static DatabaseClient.GenericExecuteSpec text(DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
