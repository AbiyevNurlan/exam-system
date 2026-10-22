package com.examportal.exam.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The order in which a session sees the questions of one block (B12b): the stored order, shuffled when the block
 * shuffles. The shuffle is seeded by the session and the block, so the same session always gets the same order:
 * after a restart of the service, and wherever the order is needed (the questions served, and the furthest
 * question reached in a block without free navigation). Questions never move between blocks.
 */
public final class SessionOrder {

    private SessionOrder() {
    }

    public static <T> List<T> of(List<T> inStoredOrder, boolean shuffle, String sessionId, Long blockId) {
        List<T> order = new ArrayList<>(inStoredOrder);
        if (shuffle && order.size() > 1) {
            Collections.shuffle(order, new Random(seed(sessionId, blockId)));
        }
        return order;
    }

    static long seed(String sessionId, Long blockId) {
        return 31L * sessionId.hashCode() + (blockId == null ? 0 : blockId);
    }
}
