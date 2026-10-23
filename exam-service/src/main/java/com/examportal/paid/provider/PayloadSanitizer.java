package com.examportal.paid.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * B13b item 3 and 13: what is kept of a provider's notification. Card data never enters the system: every field
 * whose name looks like card data (pan, card number, cvv/cvc, expiry, track, holder) is removed at any depth, and a
 * body that is not JSON is kept only as its length. At most 64 KB.
 */
public final class PayloadSanitizer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CARD_FIELDS = Set.of("pan", "card", "cardnumber", "card_number", "number", "cvv", "cvc", "cvv2",
            "securitycode", "security_code", "expiry", "exp", "expmonth", "exp_month", "expyear", "exp_year", "expiration", "track",
            "track1", "track2", "cardholder", "holder", "holdername", "cardholdername", "pin");
    private static final int MAX_BYTES = 65_536;

    private PayloadSanitizer() {
    }

    public static String sanitize(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode tree = JSON.readTree(body);
            strip(tree);
            String text = JSON.writeValueAsString(tree);
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            return bytes.length <= MAX_BYTES ? text : "{\"truncated\":true,\"bytes\":" + bytes.length + "}";
        } catch (Exception notJson) {
            return "{\"notJson\":true,\"bytes\":" + body.length + "}";
        }
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> remove = new ArrayList<>();
            for (Iterator<String> names = object.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                if (CARD_FIELDS.contains(name.toLowerCase(Locale.ROOT).replace("-", ""))) {
                    remove.add(name);
                } else {
                    strip(object.get(name));
                }
            }
            object.remove(remove);
        } else if (node != null && node.isArray()) {
            node.forEach(PayloadSanitizer::strip);
        }
    }
}
