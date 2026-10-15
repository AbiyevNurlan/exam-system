package com.examportal.exam.service;

import com.examportal.common.domain.entity.UserGroup;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.repository.UserGroupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroupAccessPolicyTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private final UserGroupRepository repository = mock(UserGroupRepository.class);
    private final GroupAccessPolicy policy = new GroupAccessPolicy(repository);

    private static UserGroup group(UUID owner) {
        return UserGroup.builder().id(7L).name("G").ownerId(owner).createdBy(owner).active(true).build();
    }

    @Test
    void adminManagesEveryGroup() {
        assertThat(GroupAccessPolicy.canManage(group(OWNER), OTHER, "ADMIN")).isTrue();
    }

    @Test
    void instructorManagesOnlyTheOwnGroup() {
        assertThat(GroupAccessPolicy.canManage(group(OWNER), OWNER, "INSTRUCTOR")).isTrue();
        assertThat(GroupAccessPolicy.canManage(group(OWNER), OTHER, "INSTRUCTOR")).isFalse();
    }

    @Test
    void studentAndUnknownRolesManageNothing() {
        assertThat(GroupAccessPolicy.canManage(group(OWNER), OWNER, "STUDENT")).isFalse();
        assertThat(GroupAccessPolicy.canManage(group(OWNER), OWNER, null)).isFalse();
        assertThat(GroupAccessPolicy.canManage(group(null), OWNER, "INSTRUCTOR")).isFalse();
        assertThat(GroupAccessPolicy.canManage(null, OWNER, "ADMIN")).isFalse();
    }

    @Test
    void listsAreLimitedToTheOwnGroupsExceptForAdmin() {
        assertThat(GroupAccessPolicy.ownerFilter(OWNER, "ADMIN")).isNull();
        assertThat(GroupAccessPolicy.ownerFilter(OWNER, "INSTRUCTOR")).isEqualTo(OWNER);
        // Nobody owns the nil UUID: other roles get empty lists
        assertThat(GroupAccessPolicy.ownerFilter(OWNER, "STUDENT")).isEqualTo(new UUID(0L, 0L));
    }

    @Test
    void theOwnerGetsTheGroup() {
        when(repository.findActiveById(7L)).thenReturn(Mono.just(group(OWNER)));
        StepVerifier.create(policy.findManageableGroup(7L, OWNER, "INSTRUCTOR"))
                .expectNextMatches(found -> found.getId() == 7L)
                .verifyComplete();
    }

    @Test
    void anotherOwnersGroupIsA404LikeAMissingOne() {
        when(repository.findActiveById(7L)).thenReturn(Mono.just(group(OWNER)));
        when(repository.findActiveById(8L)).thenReturn(Mono.empty());

        StepVerifier.create(policy.findManageableGroup(7L, OTHER, "INSTRUCTOR"))
                .expectErrorSatisfies(error -> assertNotFound(error))
                .verify();
        StepVerifier.create(policy.findManageableGroup(8L, OTHER, "INSTRUCTOR"))
                .expectErrorSatisfies(error -> assertNotFound(error))
                .verify();
    }

    private static void assertNotFound(Throwable error) {
        assertThat(error).isInstanceOf(ApplicationException.class);
        ApplicationException exception = (ApplicationException) error;
        assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception.getErrorCode()).isEqualTo("GROUP_NOT_FOUND");
    }
}
