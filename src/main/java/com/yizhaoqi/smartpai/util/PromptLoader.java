package com.yizhaoqi.smartpai.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads prompt templates from classpath and supports simple placeholder replacement.
 */
@Component
public class PromptLoader {

    private static final Logger logger = LoggerFactory.getLogger(PromptLoader.class);

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String load(String classpathLocation) {
        return cache.computeIfAbsent(classpathLocation, this::loadFromClasspath);
    }

    public String loadWithFallback(String classpathLocation, String fallback) {
        String prompt = load(classpathLocation);
        if (prompt == null || prompt.isBlank()) {
            return fallback;
        }
        return prompt;
    }

    public String render(String template, Map<String, String> variables) {
        if (template == null || template.isBlank() || variables == null || variables.isEmpty()) {
            return template;
        }
        String rendered = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String key = "{" + entry.getKey() + "}";
            String value = entry.getValue() == null ? "" : entry.getValue();
            rendered = rendered.replace(key, value);
        }
        return rendered;
    }

    private String loadFromClasspath(String classpathLocation) {
        try (InputStream inputStream = new ClassPathResource(classpathLocation).getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warn("Failed to load prompt from classpath: {}", classpathLocation, e);
            return "";
        }
    }
}
