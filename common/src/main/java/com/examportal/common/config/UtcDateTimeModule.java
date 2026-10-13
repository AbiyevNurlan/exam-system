package com.examportal.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * UTC Date-Time Module
 * Dates are stored and handled as UTC LocalDateTime (the JVM and the database run in UTC). This
 * module makes that explicit at the API boundary without changing any field or column:
 *
 * - written:  always ISO-8601 with milliseconds and "Z", e.g. 2026-10-13T07:59:31.099Z
 * - read:     a value with an offset ("Z", "+04:00") is converted to the same instant in UTC;
 *             a value without one is taken as UTC, as before
 *
 * Register it after JavaTimeModule so it replaces that module's LocalDateTime handling.
 */
public class UtcDateTimeModule extends SimpleModule {

    private static final DateTimeFormatter OUTPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    public UtcDateTimeModule() {
        super("UtcDateTimeModule");
        addSerializer(LocalDateTime.class, new Serializer());
        addDeserializer(LocalDateTime.class, new Deserializer());
    }

    /**
     * The UTC date-time a client meant by the given text
     */
    public static LocalDateTime parse(String text) {
        String value = text.trim();
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException notOffset) {
            return LocalDateTime.parse(value);
        }
    }

    public static String format(LocalDateTime value) {
        return OUTPUT.format(value.truncatedTo(ChronoUnit.MILLIS));
    }

    private static final class Serializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            generator.writeString(format(value));
        }
    }

    private static final class Deserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (LocalDateTime) context.handleUnexpectedToken(LocalDateTime.class, parser);
            }
            String text = parser.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return parse(text);
            } catch (DateTimeParseException e) {
                return (LocalDateTime) context.handleWeirdStringValue(LocalDateTime.class, text,
                        "expected an ISO-8601 date-time, with or without an offset");
            }
        }
    }
}