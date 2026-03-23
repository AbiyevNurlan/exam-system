package com.examportal.common.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * JSON Utility
 * Provides JSON serialization and deserialization operations.
 */
@Slf4j
public final class JsonUtils {

    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    private JsonUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        return mapper;
    }

    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }

    public static <T> Optional<String> toJson(T object) {
        try {
            return Optional.of(OBJECT_MAPPER.writeValueAsString(object));
        } catch (JsonProcessingException e) {
            log.error("Error converting object to JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public static <T> String toJsonOrThrow(T object) {
        try {
            return OBJECT_MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error converting object to JSON", e);
        }
    }

    public static <T> Optional<T> fromJson(String json, Class<T> clazz) {
        try {
            return Optional.of(OBJECT_MAPPER.readValue(json, clazz));
        } catch (JsonProcessingException e) {
            log.error("Error converting JSON to object: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public static <T> T fromJsonOrThrow(String json, Class<T> clazz) {
        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error converting JSON to object", e);
        }
    }

    public static <T> Optional<T> fromJson(String json, TypeReference<T> typeReference) {
        try {
            return Optional.of(OBJECT_MAPPER.readValue(json, typeReference));
        } catch (JsonProcessingException e) {
            log.error("Error converting JSON to object: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public static <T> String toPrettyJson(T object) {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error converting object to pretty JSON", e);
        }
    }

    public static <T> T convertValue(Object fromValue, Class<T> toValueType) {
        return OBJECT_MAPPER.convertValue(fromValue, toValueType);
    }
}
