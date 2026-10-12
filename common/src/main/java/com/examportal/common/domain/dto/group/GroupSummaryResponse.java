package com.examportal.common.domain.dto.group;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Group Summary Response DTO
 * What an instructor needs to pick an exam audience: id and name, nothing else.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupSummaryResponse {

    private Long id;
    private String name;
}
