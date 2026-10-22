package com.examportal.exam.service;

import com.examportal.exam.repository.GroupMembershipRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The query behind the list of people a bulk addition found in the group already, on a real PostgreSQL: only members
 * of that group among the given ids, in a stable order, at most `limit`. Skipped without Docker.
 */
class BulkAlreadyMembersTest {

    private static ExamTestStack stack;
    private static GroupMembershipRepository memberships;

    @BeforeAll
    static void start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        stack = ExamTestStack.start();
        memberships = new GroupMembershipRepository(stack.db);
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    private static long group(String name, UUID owner) {
        return stack.db.sql("INSERT INTO user_groups (name, created_by, owner_id) VALUES (:n, :o, :o) RETURNING id")
                .bind("n", name).bind("o", owner).map(row -> row.get(0, Long.class)).one().block();
    }

    private static void member(long group, UUID user, UUID by) {
        stack.db.sql("INSERT INTO user_group_members (group_id, user_id, source, added_by) VALUES (:g, :u, 'ADMIN', :a)")
                .bind("g", group).bind("u", user).bind("a", by).then().block();
    }

    @Test
    void onlyMembersOfThatGroupAmongTheGivenIdsAreFound() {
        UUID owner = UUID.randomUUID();
        long first = group("T-bulk first", owner);
        long second = group("T-bulk second", owner);
        UUID inFirst = UUID.randomUUID();
        UUID inBoth = UUID.randomUUID();
        UUID inSecondOnly = UUID.randomUUID();
        UUID nobody = UUID.randomUUID();
        member(first, inFirst, owner);
        member(first, inBoth, owner);
        member(second, inBoth, owner);
        member(second, inSecondOnly, owner);

        List<UUID> found = memberships.findMembersAmong(first, List.of(inFirst, inBoth, inSecondOnly, nobody), 50).block();
        assertThat(found).containsExactlyInAnyOrder(inFirst, inBoth);
        assertThat(memberships.countMembersAmong(first, List.of(inFirst, inBoth, inSecondOnly, nobody)).block()).isEqualTo(2);
        // Nothing is found among people who are not members
        assertThat(memberships.findMembersAmong(first, List.of(inSecondOnly, nobody), 50).block()).isEmpty();
    }

    @Test
    void theListIsCutAtTheLimitInAStableOrder() {
        UUID owner = UUID.randomUUID();
        long group = group("T-bulk limit", owner);
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID user = UUID.randomUUID();
            all.add(user);
            member(group, user, owner);
        }
        List<UUID> five = memberships.findMembersAmong(group, all, 5).block();
        assertThat(five).hasSize(5).isSortedAccordingTo(java.util.Comparator.comparing(UUID::toString));
        assertThat(memberships.findMembersAmong(group, all, 5).block()).isEqualTo(five);
        assertThat(memberships.findMembersAmong(group, all, 50).block()).hasSize(12);
    }
}
