package com.yizhaoqi.smartpai.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Utility methods for extracting and parsing JSON content from LLM responses.
 */
public final class JsonExtractor {

    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = new ObjectMapper();

    private JsonExtractor() {
    }

    /**
     * Extract the first balanced JSON object from a text response.
     */
    public static String extract(String llmResponse) {
        if (llmResponse == null) {
            return "";
        }
        String text = llmResponse.trim();
        if (text.isEmpty()) {
            return "";
        }

        int start = text.indexOf('{');
        if (start < 0) {
            return text;
        }

        boolean inString = false;
        boolean escaping = false;
        int depth = 0;
        int end = -1;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaping) {
                escaping = false;
                continue;
            }

            if (c == '\\') {
                escaping = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                }
            }
        }

        if (end > start) {
            return text.substring(start, end + 1);
        }

        int lastBrace = text.lastIndexOf('}');
        if (lastBrace > start) {
            return text.substring(start, lastBrace + 1);
        }
        return text;
    }

    public static <T> T parse(String llmResponse, Class<T> type) {
        return parse(llmResponse, type, DEFAULT_OBJECT_MAPPER);
    }

    public static <T> T parse(String llmResponse, TypeReference<T> typeReference) {
        return parse(llmResponse, typeReference, DEFAULT_OBJECT_MAPPER);
    }

    public static <T> T parse(String llmResponse, Class<T> type, ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(extract(llmResponse), type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON response", e);
        }
    }

    public static <T> T parse(String llmResponse, TypeReference<T> typeReference, ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(extract(llmResponse), typeReference);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON response", e);
        }
    }
}
