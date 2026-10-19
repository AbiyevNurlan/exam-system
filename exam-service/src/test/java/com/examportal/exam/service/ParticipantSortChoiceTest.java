package com.examportal.exam.service;

import com.examportal.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B11b: sortBy and dir of GET /api/admin/participants are a whitelist; without sortBy the older sort and
 * order behave exactly as before.
 */
class ParticipantSortChoiceTest {

    private static ParticipantService.SortChoice choice(String sortBy, String dir, String sort, String order) {
        return ParticipantService.sortChoice(sortBy, dir, sort, order);
    }

    @Test
    void sortByAndDir() {
        assertThat(choice("code", null, null, null)).isEqualTo(new ParticipantService.SortChoice("code", true));
        assertThat(choice("firstName", "desc", null, null)).isEqualTo(new ParticipantService.SortChoice("firstName", false));
        assertThat(choice(" LASTNAME ", "ASC", null, null)).isEqualTo(new ParticipantService.SortChoice("lastName", true));
        assertThat(choice("email", null, null, null)).isEqualTo(new ParticipantService.SortChoice("email", true));
        // dir defaults to asc, also for the registration time
        assertThat(choice("registeredAt", null, null, null)).isEqualTo(new ParticipantService.SortChoice("registeredAt", true));
        // sortBy wins over the older sort; order is taken as dir when dir is not given
        assertThat(choice("lastName", null, "email", "desc")).isEqualTo(new ParticipantService.SortChoice("lastName", false));
    }

    @Test
    void withoutSortByTheOlderParametersAsBefore() {
        assertThat(choice(null, null, null, null)).isEqualTo(new ParticipantService.SortChoice("createdAt", false));
        assertThat(choice("", null, "name", null)).isEqualTo(new ParticipantService.SortChoice("name", true));
        assertThat(choice(null, null, "createdAt", "asc")).isEqualTo(new ParticipantService.SortChoice("createdAt", true));
        assertThat(choice(null, null, "email", "desc")).isEqualTo(new ParticipantService.SortChoice("email", false));
    }

    @Test
    void anythingOutsideTheWhitelistIs400() {
        assertThatThrownBy(() -> choice("password_hash", null, null, null)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("sortBy");
        assertThatThrownBy(() -> choice("last_name; DROP TABLE users", null, null, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> choice("name", null, null, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> choice("code", "up", null, null)).isInstanceOf(ValidationException.class).hasMessageContaining("dir");
        assertThatThrownBy(() -> choice(null, null, "role", null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> choice(null, null, null, "sideways")).isInstanceOf(ValidationException.class);
    }
}
