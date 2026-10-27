package com.examportal.exam.config;

/**
 * The helper of EndpointRegistryTest (package-private there) for the registry test of paid open exams
 */
public final class PaidAccessHelper {

    private PaidAccessHelper() {
    }

    public static String withoutRegex(String pattern) {
        return EndpointRegistryTest.withoutRegex(pattern);
    }
}
