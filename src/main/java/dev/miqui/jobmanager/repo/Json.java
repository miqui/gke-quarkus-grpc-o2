package dev.miqui.jobmanager.repo;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;

import java.util.Map;

/** google.protobuf.Struct <-> JSON text, the representation JSONB columns are read and written as. */
public final class Json {

    private static final JsonFormat.Printer PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();
    private static final JsonFormat.Parser PARSER = JsonFormat.parser();

    private Json() {
    }

    /** Compact JSON. Callers validate first: NaN/Infinity can't be printed. */
    public static String print(Struct struct) {
        try {
            return PRINTER.print(struct);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("Struct is not representable as JSON", e);
        }
    }

    /** A JSON object read from a JSONB column. */
    public static Struct parse(String json) {
        Struct.Builder builder = Struct.newBuilder();
        try {
            PARSER.merge(json, builder);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Stored JSON is not an object", e);
        }
        return builder.build();
    }

    public static String print(Map<String, String> labels) {
        Struct.Builder struct = Struct.newBuilder();
        labels.forEach((key, value) -> struct.putFields(key, Value.newBuilder().setStringValue(value).build()));
        return print(struct.build());
    }

    public static Map<String, String> labels(String json) {
        var labels = new java.util.TreeMap<String, String>();
        parse(json).getFieldsMap().forEach((key, value) -> labels.put(key, value.getStringValue()));
        return labels;
    }

    public static Struct object(Map<String, Object> fields) {
        Struct.Builder struct = Struct.newBuilder();
        fields.forEach((key, value) -> struct.putFields(key, value(value)));
        return struct.build();
    }

    private static Value value(Object value) {
        return switch (value) {
            case null -> Value.newBuilder().setNullValueValue(0).build();
            case String s -> Value.newBuilder().setStringValue(s).build();
            case Number n -> Value.newBuilder().setNumberValue(n.doubleValue()).build();
            case Boolean b -> Value.newBuilder().setBoolValue(b).build();
            case Struct s -> Value.newBuilder().setStructValue(s).build();
            default -> throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
        };
    }
}
