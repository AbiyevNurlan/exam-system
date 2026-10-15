package com.examportal.auth.service;

import com.examportal.common.domain.dto.auth.ChangePasswordRequest;
import com.examportal.common.domain.dto.auth.UpdateProfileRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B9: the request bodies of PUT /auth/profile and POST /auth/change-password (the policy of the new
 * password is the registration policy; no password ever reaches toString()).
 */
class AccountRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> invalidFields(Object request) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test
    void theNewPasswordFollowsTheRegistrationPolicy() {
        for (String weak : new String[]{"short1A", "alllowercase1", "ALLUPPERCASE1", "NoDigitsHere", ""}) {
            assertThat(invalidFields(ChangePasswordRequest.builder().currentPassword("x").newPassword(weak).build()))
                    .as(weak).contains("newPassword");
        }
        assertThat(invalidFields(ChangePasswordRequest.builder().currentPassword("x").newPassword("Strong1pass").build())).isEmpty();
        assertThat(invalidFields(ChangePasswordRequest.builder().currentPassword("").newPassword("Strong1pass").build()))
                .containsExactly("currentPassword");
    }

    @Test
    void passwordsNeverAppearInToString() {
        String text = ChangePasswordRequest.builder().currentPassword("Old1secret").newPassword("New1secret").build().toString();
        assertThat(text).doesNotContain("Old1secret").doesNotContain("New1secret");
    }

    @Test
    void profileNamesAndPhoneAreValidated() {
        assertThat(invalidFields(UpdateProfileRequest.builder().firstName("A").lastName("B").build()))
                .containsExactlyInAnyOrder("firstName", "lastName");
        assertThat(invalidFields(UpdateProfileRequest.builder().firstName("Ali").lastName("Veli").phone("abc").build()))
                .containsExactly("phone");
        assertThat(invalidFields(UpdateProfileRequest.builder().firstName("Ali").lastName("Veli").phone("").build())).isEmpty();
        assertThat(invalidFields(UpdateProfileRequest.builder().firstName("Ali").lastName("Veli").phone("+994 50 123 45 67").build())).isEmpty();
    }

    @Test
    void theFrontendNamePhoneAndTheEntityNamePhoneNumberAreBothAccepted() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThat(json.readValue("{\"firstName\":\"Ali\",\"lastName\":\"Veli\",\"phone\":\"+994501234567\"}",
                UpdateProfileRequest.class).getPhone()).isEqualTo("+994501234567");
        assertThat(json.readValue("{\"firstName\":\"Ali\",\"lastName\":\"Veli\",\"phoneNumber\":\"+994501234567\"}",
                UpdateProfileRequest.class).getPhone()).isEqualTo("+994501234567");
    }
}
