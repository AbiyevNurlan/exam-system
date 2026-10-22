package com.examportal.common.domain.dto.profile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Map;

/**
 * Profile picture DTOs (/api/me/avatar). The file goes straight from the browser to the storage with a presigned
 * URL, like question media; only the own picture can be read or changed.
 */
public final class AvatarDtos {

    private AvatarDtos() {
    }

    /** What the browser is about to upload: image/jpeg, image/png or image/webp, its exact size in bytes */
    public record UploadRequest(@NotBlank String contentType, @NotNull @Positive Long sizeBytes) {
    }

    /** Where to PUT the file (exactly with these headers) and for how many seconds the URL is valid */
    public record Upload(String uploadUrl, String method, Map<String, String> headers, int expiresIn) {
    }

    /** The picture: a short-lived URL, or url = null when the account has none */
    public record Avatar(String url, Integer expiresIn) {

        public static Avatar none() {
            return new Avatar(null, null);
        }
    }
}
