package com.examportal.common.domain.profile;

import com.examportal.common.domain.dto.profile.ParticipantProfileDtos;
import com.examportal.common.domain.dto.profile.ParticipantProfileDtos.UpdateRequest;
import com.examportal.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B11: profile text is trimmed and cleaned of HTML, limits hold after cleaning, the category must be an
 * active one, required fields come from configuration, and the change description names every change.
 */
class ParticipantProfileRulesTest {

    private static final List<String> CATEGORIES = List.of("STUDENT", "UNIVERSITY_STUDENT", "SCHOOL_STUDENT", "EMPLOYEE", "INTERN", "OTHER");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private static UpdateRequest normalize(UpdateRequest request, Map<String, List<String>> required) {
        return ParticipantProfileRules.normalize(request, CATEGORIES, required, TODAY);
    }

    @Test
    void textIsTrimmedAndCleanedOfMarkupAndScripts() {
        assertThat(ProfileText.clean("city", "  Bakı  ", 100)).isEqualTo("Bakı");
        assertThat(ProfileText.clean("city", "<b>Bakı</b>", 100)).isEqualTo("Bakı");
        assertThat(ProfileText.clean("institution", "<script>alert(1)</script>ADA", 200)).isEqualTo("alert(1) ADA");
        assertThat(ProfileText.clean("institution", "<img src=x onerror=alert(1)>BDU", 200)).isEqualTo("BDU");
        assertThat(ProfileText.clean("institution", "ADA <!-- note --> University", 200)).isEqualTo("ADA University");
        assertThat(ProfileText.clean("position", "x < y > z \"q\" `w`", 100)).isEqualTo("x z q w");
        assertThat(ProfileText.clean("workplace", "javascript:alert(1)", 200)).isEqualTo("javascript alert(1)");
        assertThat(ProfileText.clean("city", "Ba\u200Bkı\u0000\n\tŞəki", 100)).isEqualTo("Ba kı Şəki");
        assertThat(ProfileText.clean("city", "   ", 100)).isNull();
        assertThat(ProfileText.clean("city", "<p></p>", 100)).isNull();
        assertThat(ProfileText.clean("city", null, 100)).isNull();
    }

    @Test
    void theLimitHoldsAfterCleaning() {
        // 100 letters inside a tag-heavy text are fine; 101 are not
        assertThat(ProfileText.clean("city", "<i>" + "a".repeat(100) + "</i>", 100)).hasSize(100);
        assertThatThrownBy(() -> ProfileText.clean("city", "a".repeat(101), 100))
                .isInstanceOf(ValidationException.class).hasMessageContaining("city");
    }

    @Test
    void categoryIsRequiredUpperCasedAndMustBeActive() {
        UpdateRequest normalized = normalize(UpdateRequest.builder().category(" university_student ").institution(" ADA ").build(), Map.of());
        assertThat(normalized.getCategory()).isEqualTo("UNIVERSITY_STUDENT");
        assertThat(normalized.getInstitution()).isEqualTo("ADA");
        assertThatThrownBy(() -> normalize(UpdateRequest.builder().build(), Map.of()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("category");
        assertThatThrownBy(() -> normalize(UpdateRequest.builder().category("ALIEN").build(), Map.of()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("category");
        assertThatThrownBy(() -> normalize(null, Map.of())).isInstanceOf(ValidationException.class);
    }

    @Test
    void onlyTheCategoryIsRequiredByDefaultTheRestByConfiguration() {
        assertThat(normalize(UpdateRequest.builder().category("INTERN").build(), Map.of()).getCategory()).isEqualTo("INTERN");
        Map<String, List<String>> required = Map.of("UNIVERSITY_STUDENT", List.of("institution", "facultyOrDepartment"));
        assertThatThrownBy(() -> normalize(UpdateRequest.builder().category("UNIVERSITY_STUDENT").institution("ADA").build(), required))
                .isInstanceOf(ValidationException.class).hasMessageContaining("facultyOrDepartment");
        // A required field that is only markup is empty
        assertThatThrownBy(() -> normalize(UpdateRequest.builder().category("UNIVERSITY_STUDENT").institution("<b></b>")
                .facultyOrDepartment("IT").build(), required))
                .isInstanceOf(ValidationException.class).hasMessageContaining("institution");
        assertThat(normalize(UpdateRequest.builder().category("UNIVERSITY_STUDENT").institution("ADA").facultyOrDepartment("IT").build(),
                required).getFacultyOrDepartment()).isEqualTo("IT");
        // Other categories are not affected
        assertThat(normalize(UpdateRequest.builder().category("EMPLOYEE").build(), required).getCategory()).isEqualTo("EMPLOYEE");
    }

    @Test
    void birthDateBetween1900AndToday() {
        assertThat(normalize(UpdateRequest.builder().category("OTHER").birthDate(LocalDate.of(2005, 3, 1)).build(), Map.of())
                .getBirthDate()).isEqualTo(LocalDate.of(2005, 3, 1));
        assertThatThrownBy(() -> normalize(UpdateRequest.builder().category("OTHER").birthDate(TODAY.plusDays(1)).build(), Map.of()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("birthDate");
        assertThatThrownBy(() -> normalize(UpdateRequest.builder().category("OTHER").birthDate(LocalDate.of(1899, 12, 31)).build(), Map.of()))
                .isInstanceOf(ValidationException.class).hasMessageContaining("birthDate");
    }

    @Test
    void theChangeDescriptionNamesBeforeAndAfter() {
        UpdateRequest first = UpdateRequest.builder().category("STUDENT").city("Bakı").build();
        assertThat(ParticipantProfileRules.describeChanges(null, first)).isEqualTo("category: - -> 'STUDENT'; city: - -> 'Bakı'");

        ParticipantProfileDtos.Profile stored = ParticipantProfileDtos.Profile.builder().category("STUDENT").city("Bakı").build();
        assertThat(ParticipantProfileRules.describeChanges(stored, first)).isEmpty();
        UpdateRequest next = UpdateRequest.builder().category("INTERN").workplace("Azercell").birthDate(LocalDate.of(2001, 1, 2)).build();
        assertThat(ParticipantProfileRules.describeChanges(stored, next))
                .isEqualTo("category: 'STUDENT' -> 'INTERN'; workplace: - -> 'Azercell'; city: 'Bakı' -> -; birthDate: - -> 2001-01-02");
    }
}
