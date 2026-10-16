package com.examportal.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * participant-profile.required-fields: per category the profile fields (JSON names, e.g. institution,
 * courseOrGrade) that must be filled. A category without an entry needs only the category itself,
 * which is the default for all of them.
 *
 * participant-profile:
 *   required-fields:
 *     UNIVERSITY_STUDENT: [institution, facultyOrDepartment]
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "participant-profile")
public class ParticipantProfileProperties {

    private Map<String, List<String>> requiredFields = new LinkedHashMap<>();
}
