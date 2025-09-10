package org.openl.rules.datatype.gen;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.openl.gen.FieldDescription;
import org.openl.gen.writers.DefaultValue;
import org.openl.rules.convertor.IString2DataConvertor;
import org.openl.rules.convertor.String2DataConvertorFactory;

public class FieldDescriptionBuilder {

    private static final Map<String, Class<?>> CLASSMAP;

    static {
        Map<String, Class<?>> classMap = new HashMap<>();
        classMap.put("int", int.class);
        classMap.put("long", long.class);
        classMap.put("char", char.class);
        classMap.put("short", short.class);
        classMap.put("byte", byte.class);
        classMap.put("double", double.class);
        classMap.put("float", float.class);
        classMap.put("boolean", boolean.class);
        CLASSMAP = Collections.unmodifiableMap(classMap);
    }

    private final String typeName;
    private String defaultValueAsString;
    private Object defaultValue;
    private String contextPropertyName;
    private boolean isTransient;
    private String description;
    private String[] allowableValues;
    private String example;
    private Boolean mandatory;

    private FieldDescriptionBuilder(String typeName) {
        this.typeName = typeName;
    }

    public static FieldDescriptionBuilder create(String typeName) {
        return new FieldDescriptionBuilder(typeName);
    }

    public FieldDescriptionBuilder setDefaultValueAsString(String defaultValueAsString) {
        this.defaultValueAsString = defaultValueAsString;
        return this;
    }

    public FieldDescriptionBuilder setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    public FieldDescriptionBuilder setContextPropertyName(String contextPropertyName) {
        this.contextPropertyName = contextPropertyName;
        return this;
    }

    public FieldDescriptionBuilder setTransient(boolean isTransient) {
        this.isTransient = isTransient;
        return this;
    }

    public FieldDescriptionBuilder setDescriptionValue(String description) {
        this.description = description;
        return this;
    }

    public FieldDescriptionBuilder setAllowableValues(String[] allowableValues) {
        this.allowableValues = allowableValues;
        return this;
    }

    public FieldDescriptionBuilder setExampleValue(String example) {
        this.example = example;
        return this;
    }

    public FieldDescriptionBuilder setMandatoryValue(Boolean mandatory) {
        this.mandatory = mandatory;
        return this;
    }

    public FieldDescription build() {
        return new FieldDescription(typeName, getDefaultValue(), defaultValueAsString, contextPropertyName, null, description, allowableValues, example, Boolean.TRUE.equals(mandatory), isTransient);
    }

    /**
     * Returns the actual type of the field. Is never null.
     *
     * @return
     */
    private Class<?> getType() {
        Class<?> cl = CLASSMAP.get(typeName);
        if (cl != null) {
            return cl;
        }
        try {
            return Class.forName(typeName);
        } catch (ClassNotFoundException e) {
            return Object.class; // For datatypes
        }
    }

    /**
     * Gets the default value for current field.<br>
     * Converts the stiraging String value to the type of current field (see {@link #getType()}).<br>
     * <br >
     * In case {@link #getType()} method returns one of the primitive classes,<br>
     * the default value will be represented in the wrapper class for this primitive, e.g.<br>
     * {@link #getType()} returns <code>int.class</code> and the default value will be wrapped<br>
     * with {@link Integer}.
     */
    private Object getDefaultValue() {
        if (defaultValue == null) {
            if (defaultValueAsString != null) {
                if (DefaultValue.DEFAULT.equals(defaultValueAsString)) {
                    // Keep the default value key word for all the types of the field as the default value.
                    //
                    defaultValue = DefaultValue.DEFAULT;
                } else {
                    if (typeName.startsWith("[[")) {
                        throw new IllegalStateException("Multi-dimensional arrays are not supported.");
                    }
                    IString2DataConvertor convertor = String2DataConvertorFactory.getConvertor(getType());
                    defaultValue = convertor.parse(defaultValueAsString, null);
                }
            }
        }
        return defaultValue;
    }
}
