package com.yizhaoqi.smartpai.mcp.skill;

import lombok.Data;
import java.util.Map;

/**
 * 技能参数定义（JSON Schema 格式）
 * 用于 Function Calling 协议
 */
@Data
public class SkillParameterSchema {

    private String type = "object";
    private Map<String, PropertySchema> properties;
    private String[] required;

    @Data
    public static class PropertySchema {
        private String type;
        private String description;
        private String[] enumValues;
        private Object defaultValue;
        private Boolean required;

        public static PropertySchema string(String description) {
            PropertySchema schema = new PropertySchema();
            schema.setType("string");
            schema.setDescription(description);
            return schema;
        }

        public static PropertySchema integer(String description) {
            PropertySchema schema = new PropertySchema();
            schema.setType("integer");
            schema.setDescription(description);
            return schema;
        }

        public static PropertySchema bool(String description) {
            PropertySchema schema = new PropertySchema();
            schema.setType("boolean");
            schema.setDescription(description);
            return schema;
        }

        public static PropertySchema number(String description) {
            PropertySchema schema = new PropertySchema();
            schema.setType("number");
            schema.setDescription(description);
            return schema;
        }

        public static PropertySchema array(String description, String itemType) {
            PropertySchema schema = new PropertySchema();
            schema.setType("array");
            schema.setDescription(description);
            return schema;
        }

        public PropertySchema enums(String... values) {
            this.enumValues = values;
            return this;
        }

        public PropertySchema defaultValue(Object value) {
            this.defaultValue = value;
            return this;
        }

        public PropertySchema required(boolean required) {
            this.required = required;
            return this;
        }
    }

    public static SkillParameterSchema create() {
        return new SkillParameterSchema();
    }

    public SkillParameterSchema property(String name, PropertySchema schema) {
        if (properties == null) {
            properties = new java.util.LinkedHashMap<>();
        }
        properties.put(name, schema);
        return this;
    }

    public SkillParameterSchema required(String... names) {
        this.required = names;
        return this;
    }
}
