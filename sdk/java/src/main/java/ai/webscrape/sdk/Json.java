package ai.webscrape.sdk;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared Jackson configuration and small null-tolerant tree helpers.
 * Package-private: not part of the public API.
 */
final class Json {

    /**
     * One mapper for the whole SDK.
     *
     * <ul>
     *   <li>Unknown response properties are ignored (forward compatibility).</li>
     *   <li>Null fields are omitted on serialization (optional-field omission).</li>
     *   <li>Property mapping is driven purely by fields, so request/response getters
     *       never shadow or double-register a wire property. Every wire name is
     *       declared explicitly with {@code @JsonProperty}.</li>
     * </ul>
     */
    static final ObjectMapper MAPPER = build();

    private Json() {
    }

    private static ObjectMapper build() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setVisibility(mapper.getVisibilityChecker()
                .withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                .withVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                .withVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY));
        return mapper;
    }

    static byte[] write(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new WebscrapeException("failed to serialize request body", e);
        }
    }

    /** Parse bytes to a tree, returning {@code null} if the body is empty or not JSON. */
    static JsonNode tryParse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Read a textual field, or {@code null} if absent/null/non-scalar. */
    static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        return value.asText();
    }

    /** Read an integer field, or {@code null} if absent/null/non-numeric. */
    static Integer integer(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.asInt();
    }
}
