package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.media.MediaResponse;
import com.examportal.common.domain.dto.media.MediaUploadRequest;
import com.examportal.common.domain.dto.media.MediaUploadResponse;
import com.examportal.common.domain.dto.media.MediaUrlResponse;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.service.MediaService;
import com.examportal.exam.service.InstructorLimitsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Media Controller
 * Upload and management of question media for ADMIN and INSTRUCTOR. The role is enforced by path
 * in SecurityConfig (/api/media/**); @PreAuthorize is a second layer.
 * Participants never use these endpoints: they get media through their exam session only.
 */
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
@Tag(name = "Media", description = "Question and option media: direct upload with presigned URLs")
@SecurityRequirement(name = "bearerAuth")
public class MediaController {

    private final MediaService mediaService;
    private final InstructorLimitsService limitsService;

    @PostMapping("/upload-url")
    @Operation(summary = "Get an upload URL", description = "Registers a PENDING asset and returns a presigned URL (valid 10 minutes). "
            + "PUT the file to it with exactly the returned headers, then call confirm. "
            + "Allowed: IMAGE jpeg/png/webp up to 5 MB, AUDIO mp3/ogg/m4a up to 20 MB, VIDEO mp4 up to 200 MB. "
            + "altText is shown to participants: on an option image it must not reveal the answer.")
    public Mono<ApiResponse<MediaUploadResponse>> createUpload(
            @Valid @RequestBody MediaUploadRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        // B13: a teacher's limit of media bytes (ADMIN has none)
        long size = request.getSizeBytes() == null ? 0 : request.getSizeBytes();
        return limitsService.requireRoom(principal.getId(), principal.getRole(), InstructorLimitsService.MEDIA_BYTES, size)
                .then(Mono.defer(() -> mediaService.createUpload(request, principal.getId())))
                .map(ApiResponse::success);
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm an upload", description = "Checks the stored file (size and magic bytes against the declared type) and makes the asset ACTIVE. "
            + "409 MEDIA_NOT_UPLOADED while nothing was uploaded; 400 MEDIA_INVALID deletes the file and the asset.")
    public Mono<ApiResponse<MediaResponse>> confirm(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {

        return mediaService.confirm(id, principal.getId(), principal.getRole())
                .map(media -> ApiResponse.success(media, "Media confirmed"));
    }

    @GetMapping("/{id}/preview-url")
    @Operation(summary = "Get a preview URL", description = "Short-lived download URL for the owner or an ADMIN (ACTIVE assets only)")
    public Mono<ApiResponse<MediaUrlResponse>> previewUrl(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {

        return mediaService.previewUrl(id, principal.getId(), principal.getRole())
                .map(ApiResponse::success);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete media", description = "Owner or ADMIN. 409 MEDIA_IN_USE while a published exam uses it; otherwise the asset is soft-deleted, "
            + "removed from draft questions and options, and the file is deleted.")
    public Mono<ApiResponse<Void>> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {

        return mediaService.delete(id, principal.getId(), principal.getRole())
                .then(Mono.fromSupplier(() -> ApiResponse.<Void>success(null, "Media deleted")));
    }
}
