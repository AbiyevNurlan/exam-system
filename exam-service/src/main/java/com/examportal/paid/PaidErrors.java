package com.examportal.paid;

import com.examportal.common.exception.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * The errors of paid open exams (B13b), in the body format of every other error. Messages carry no personal data,
 * token or signature.
 */
public final class PaidErrors {

    private PaidErrors() {
    }

    public static final String FEATURE_DISABLED = "FEATURE_DISABLED";

    private static ApplicationException of(HttpStatus status, String code, String message) {
        return new ApplicationException(message, status, code);
    }

    public static ApplicationException examNotFound() {
        return of(HttpStatus.NOT_FOUND, "EXAM_NOT_FOUND", "Exam not found");
    }

    public static ApplicationException invalidSettings(String why) {
        return of(HttpStatus.BAD_REQUEST, "PAID_SETTINGS_INVALID", why);
    }

    public static ApplicationException hasSessions() {
        return of(HttpStatus.CONFLICT, "EXAM_HAS_SESSIONS", "The exam already has attempts: its access mode cannot change");
    }

    public static ApplicationException hasOrders() {
        return of(HttpStatus.CONFLICT, "PAID_EXAM_HAS_ORDERS", "The exam has orders that are not closed: it stays paid");
    }

    public static ApplicationException examClosed() {
        return of(HttpStatus.CONFLICT, "EXAM_NOT_CHANGEABLE", "The exam is completed, cancelled or archived");
    }

    public static ApplicationException salesClosed() {
        return of(HttpStatus.CONFLICT, "SALES_CLOSED", "Sales of this exam are closed");
    }

    public static ApplicationException soldOut() {
        return of(HttpStatus.CONFLICT, "SOLD_OUT", "No seats are left");
    }

    public static ApplicationException alreadyEntitled() {
        return of(HttpStatus.CONFLICT, "ALREADY_ENTITLED", "The guest may already take this exam");
    }

    public static ApplicationException orderNotFound() {
        return of(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found");
    }

    public static ApplicationException transitionNotAllowed(String from, String to) {
        return of(HttpStatus.CONFLICT, "ORDER_STATUS_CONFLICT", "The order cannot go from " + from + " to " + to);
    }

    public static ApplicationException refundNotAllowed(String why) {
        return of(HttpStatus.CONFLICT, "REFUND_NOT_ALLOWED", why);
    }

    public static ApplicationException attemptStarted() {
        return of(HttpStatus.CONFLICT, "ATTEMPT_STARTED", "The guest has started the exam: a refund needs override with a reason");
    }

    public static ApplicationException reasonRequired() {
        return of(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "A reason (3-500 characters) is required");
    }

    public static ApplicationException notEntitled() {
        return of(HttpStatus.FORBIDDEN, "NOT_ENTITLED", "No paid access to this exam");
    }

    public static ApplicationException guestBlocked() {
        return of(HttpStatus.FORBIDDEN, "GUEST_BLOCKED", "This guest is blocked");
    }

    public static ApplicationException guestNotFound() {
        return of(HttpStatus.NOT_FOUND, "GUEST_NOT_FOUND", "Guest not found");
    }

    public static ApplicationException guestDirectoryUnavailable() {
        return of(HttpStatus.SERVICE_UNAVAILABLE, "GUEST_DIRECTORY_UNAVAILABLE", "The guest directory cannot be reached");
    }

    public static ApplicationException providerUnavailable() {
        return of(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_PROVIDER_UNAVAILABLE", "No payment provider is available");
    }

    public static ApplicationException providerError() {
        return of(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", "The payment provider did not answer as expected");
    }

    public static ApplicationException signatureInvalid() {
        return of(HttpStatus.UNAUTHORIZED, "SIGNATURE_INVALID", "The signature of the notification is not valid");
    }

    public static ApplicationException invalidFilter(String field, String why) {
        return of(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed for field '" + field + "': " + why);
    }
}
