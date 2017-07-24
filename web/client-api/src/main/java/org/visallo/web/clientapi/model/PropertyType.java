package org.visallo.web.clientapi.model;

import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;

public enum PropertyType {
    DATE("date"),
    DATE_TIME("dateTime"),
    STRING("string"),
    GEO_LOCATION("geoLocation"),
    IMAGE("image"),
    BINARY("binary"),
    CURRENCY("currency"),
    DOUBLE("double"),
    BOOLEAN("boolean"),
    INTEGER("integer"),
    DIRECTORY_ENTITY("directory/entity"),
    EXTENDED_DATA_TABLE("extendedDataTable");

    public static final String VERTEXIUM_TYPE_GEO_POINT = "org.vertexium.type.GeoPoint";

    private final String text;

    PropertyType(String text) {
        this.text = text;
    }

    @Override
    public String toString() {
        return this.text;
    }

    @JsonValue
    public String getText() {
        return text;
    }

    public static PropertyType convert(String property) {
        return convert(property, STRING);
    }

    public static PropertyType convert(String property, PropertyType defaultValue) {
        for (PropertyType pt : PropertyType.values()) {
            if (pt.toString().equalsIgnoreCase(property)) {
                return pt;
            }
        }
        return defaultValue;
    }

    public static Class getTypeClass(PropertyType propertyType) {
        switch (propertyType) {
            case DATE:
            case DATE_TIME:
                return Date.class;
            case STRING:
            case DIRECTORY_ENTITY:
                return String.class;
            case GEO_LOCATION:
                try {
                    return Class.forName(VERTEXIUM_TYPE_GEO_POINT);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Could not find class: " + VERTEXIUM_TYPE_GEO_POINT);
                }
            case EXTENDED_DATA_TABLE:
                return Iterable.class;
            case IMAGE:
                return byte[].class;
            case BINARY:
                return byte[].class;
            case CURRENCY:
                return BigDecimal.class;
            case BOOLEAN:
                return Boolean.class;
            case DOUBLE:
                return Double.class;
            case INTEGER:
                return Integer.class;
            default:
                throw new RuntimeException("Unhandled property type: " + propertyType);
        }
    }
}
