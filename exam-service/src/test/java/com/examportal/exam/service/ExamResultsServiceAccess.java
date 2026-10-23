package com.examportal.exam.service;

import com.examportal.common.domain.dto.SessionSummaryResponse;

import java.util.List;

/**
 * The package-private pieces of the results export, for tests in other packages
 */
public final class ExamResultsServiceAccess {

    private ExamResultsServiceAccess() {
    }

    public static List<Object> xlsxRow(int number, SessionSummaryResponse row) {
        return ExamResultsService.xlsxRow(number, row);
    }

    public static String duration(Long seconds) {
        return ExamResultsService.duration(seconds);
    }
}
