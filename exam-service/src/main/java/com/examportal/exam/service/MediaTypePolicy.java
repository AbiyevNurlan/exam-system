package com.examportal.exam.service;

import com.examportal.common.domain.entity.MediaKind;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Media Type Policy
 * Which files may be uploaded: content types and size limit per kind (exam.media.*), and whether
 * the first bytes of a stored file really are the declared type. The declared content type and
 * any file extension are never trusted; only these magic bytes decide.
 */
@Component
public class MediaTypePolicy {

    /** How many leading bytes matchesContent needs */
    public static final int SIGNATURE_BYTES = 16;

    private static final Map<MediaKind, Set<String>> ALLOWED = Map.of(
            MediaKind.IMAGE, Set.of("image/jpeg", "image/png", "image/webp"),
            MediaKind.AUDIO, Set.of("audio/mpeg", "audio/ogg", "audio/mp4"),
            MediaKind.VIDEO, Set.of("video/mp4"));

    // ISO media (ftyp) brands of still-image formats; never accepted as audio or video
    private static final Set<String> IMAGE_BRANDS = Set.of("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis");

    @Value("${exam.media.image-max-bytes:5242880}")
    private long imageMaxBytes;

    @Value("${exam.media.audio-max-bytes:20971520}")
    private long audioMaxBytes;

    @Value("${exam.media.video-max-bytes:209715200}")
    private long videoMaxBytes;

    public boolean isAllowed(MediaKind kind, String contentType) {
        return contentType != null && ALLOWED.get(kind).contains(contentType);
    }

    public long maxBytes(MediaKind kind) {
        return switch (kind) {
            case IMAGE -> imageMaxBytes;
            case AUDIO -> audioMaxBytes;
            case VIDEO -> videoMaxBytes;
        };
    }

    /**
     * Whether the leading bytes of a file are what the content type says
     */
    public boolean matchesContent(String contentType, byte[] head) {
        if (head == null) {
            return false;
        }
        return switch (contentType) {
            case "image/jpeg" -> startsWith(head, 0xFF, 0xD8, 0xFF);
            case "image/png" -> startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/webp" -> ascii(head, 0, 4).equals("RIFF") && ascii(head, 8, 4).equals("WEBP");
            // MP3: an ID3v2 tag, or an MPEG audio frame header (11 sync bits, a valid layer)
            case "audio/mpeg" -> ascii(head, 0, 3).equals("ID3")
                    || (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0 && (head[1] & 0x06) != 0);
            case "audio/ogg" -> ascii(head, 0, 4).equals("OggS");
            // M4A and MP4 are the same ISO container: a "ftyp" box first, with a non-image brand
            case "audio/mp4", "video/mp4" -> ascii(head, 4, 4).equals("ftyp")
                    && head.length >= 12 && !IMAGE_BRANDS.contains(ascii(head, 8, 4));
            default -> false;
        };
    }

    private boolean startsWith(byte[] head, int... signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((head[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private String ascii(byte[] head, int offset, int length) {
        return head.length < offset + length ? "" : new String(head, offset, length, StandardCharsets.ISO_8859_1);
    }
}