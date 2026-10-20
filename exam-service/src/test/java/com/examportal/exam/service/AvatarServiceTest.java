package com.examportal.exam.service;

import com.examportal.common.domain.dto.profile.AvatarDtos;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.repository.AvatarRepository;
import com.examportal.exam.storage.MediaStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The own profile picture on a real PostgreSQL (all migrations, V20 user_avatars) with the object storage mocked:
 * only JPEG, PNG and WebP up to 2 MB, confirmation checks size and magic bytes, a new picture replaces the old one
 * and deletes its file, a refused upload is deleted, every user sees only the own picture.
 */
class AvatarServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52};
    private static final byte[] TEXT = "<html>not an image".getBytes();

    private static ExamTestStack stack;
    private static AvatarService service;
    private static final MediaStorage storage = mock(MediaStorage.class);

    private UUID user;

    @BeforeAll
    static void start() {
        stack = ExamTestStack.start();
        service = new AvatarService(new AvatarRepository(stack.db), storage, new MediaTypePolicy(), stack.tx,
                2_097_152L, 600, 3600);
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    @BeforeEach
    void setUp() {
        reset(storage);
        user = UUID.randomUUID();
        when(storage.isConfigured()).thenReturn(true);
        when(storage.presignUpload(anyString(), anyString(), anyLong(), any())).thenAnswer(call -> "put:" + call.getArgument(0));
        when(storage.presignDownload(anyString(), any())).thenAnswer(call -> "get:" + call.getArgument(0));
        when(storage.delete(anyString())).thenReturn(Mono.empty());
    }

    private String upload(String type, long size) {
        AvatarDtos.Upload upload = service.createUpload(user, new AvatarDtos.UploadRequest(type, size)).block();
        assertThat(upload.method()).isEqualTo("PUT");
        assertThat(upload.headers()).containsEntry("Content-Type", type);
        return upload.uploadUrl().substring("put:".length());
    }

    private void stored(String key, long size, byte[] head) {
        when(storage.findSize(key)).thenReturn(Mono.just(size));
        when(storage.readFirstBytes(eq(key), anyInt())).thenReturn(Mono.just(head));
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    @Test
    void anAccountWithoutAPictureHasNone() {
        assertThat(service.get(user).block().url()).isNull();
    }

    @Test
    void aConfirmedImageBecomesThePictureAndReplacesTheOldOne() {
        String first = upload("image/png", 1000);
        assertThat(first).startsWith("avatars/");
        // Uploaded but not confirmed: still no picture
        assertThat(service.get(user).block().url()).isNull();
        stored(first, 1000, PNG);
        assertThat(service.confirm(user).block().url()).isEqualTo("get:" + first);
        assertThat(service.get(user).block().url()).isEqualTo("get:" + first);
        verify(storage, never()).delete(first);

        String second = upload("image/png", 2000);
        stored(second, 2000, PNG);
        service.confirm(user).block();
        assertThat(service.get(user).block().url()).isEqualTo("get:" + second);
        verify(storage).delete(first);
        // Nothing left to confirm
        StepVerifier.create(service.confirm(user)).expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("MEDIA_NOT_FOUND")).verify();
    }

    @Test
    void onlyImagesUpTo2MbMayBeUploaded() {
        StepVerifier.create(service.createUpload(user, new AvatarDtos.UploadRequest("image/gif", 100L)))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("MEDIA_TYPE_NOT_ALLOWED")).verify();
        StepVerifier.create(service.createUpload(user, new AvatarDtos.UploadRequest("video/mp4", 100L)))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("MEDIA_TYPE_NOT_ALLOWED")).verify();
        StepVerifier.create(service.createUpload(user, new AvatarDtos.UploadRequest("image/jpeg", 2_097_153L)))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("MEDIA_TOO_LARGE")).verify();
        assertThat(upload("image/webp", 2_097_152L)).startsWith("avatars/");
    }

    @Test
    void aFileThatIsNotTheDeclaredImageIsRefusedAndDeleted() {
        String good = upload("image/png", 1000);
        stored(good, 1000, PNG);
        service.confirm(user).block();

        String fake = upload("image/png", 500);
        stored(fake, 500, TEXT);
        StepVerifier.create(service.confirm(user)).expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("MEDIA_INVALID")).verify();
        verify(storage).delete(fake);
        // The picture before stays
        assertThat(service.get(user).block().url()).isEqualTo("get:" + good);

        // A size other than the declared one is refused too
        String larger = upload("image/png", 500);
        stored(larger, 501, PNG);
        StepVerifier.create(service.confirm(user)).expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("MEDIA_INVALID")).verify();
        verify(storage).delete(larger);
    }

    @Test
    void anUploadThatDidNotArriveStaysUntilItDoes() {
        String key = upload("image/jpeg", 300);
        when(storage.findSize(key)).thenReturn(Mono.empty());
        StepVerifier.create(service.confirm(user)).expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("MEDIA_NOT_UPLOADED")).verify();
        stored(key, 300, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
        assertThat(service.confirm(user).block().url()).isEqualTo("get:" + key);
    }

    @Test
    void aNewUploadDropsTheOneThatWasNeverConfirmed() {
        String abandoned = upload("image/png", 100);
        String next = upload("image/png", 200);
        verify(storage).delete(abandoned);
        stored(next, 200, PNG);
        assertThat(service.confirm(user).block().url()).isEqualTo("get:" + next);
    }

    @Test
    void removingThePictureDeletesItsFileAndIsRepeatable() {
        String key = upload("image/png", 100);
        stored(key, 100, PNG);
        service.confirm(user).block();
        service.delete(user).block();
        verify(storage).delete(key);
        assertThat(service.get(user).block().url()).isNull();
        StepVerifier.create(service.delete(user)).verifyComplete();
    }

    @Test
    void everyUserHasOnlyTheOwnPicture() {
        String key = upload("image/png", 100);
        stored(key, 100, PNG);
        service.confirm(user).block();
        UUID other = UUID.randomUUID();
        assertThat(service.get(other).block().url()).isNull();
        StepVerifier.create(service.confirm(other)).expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("MEDIA_NOT_FOUND")).verify();
        service.delete(other).block();
        assertThat(service.get(user).block().url()).isEqualTo("get:" + key);
    }

    @Test
    void withoutStorageThereIsNoPictureAndNoUpload() {
        when(storage.isConfigured()).thenReturn(false);
        assertThat(service.get(user).block().url()).isNull();
        StepVerifier.create(service.createUpload(user, new AvatarDtos.UploadRequest("image/png", 100L)))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("MEDIA_STORAGE_UNAVAILABLE")).verify();
    }
}
