package dev.miqui.jobmanager.validation;

import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.google.rpc.BadRequest.FieldViolation;
import dev.miqui.jobmanager.error.ApiException;
import dev.miqui.jobmanager.repo.Json;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Collects every field violation of one request, then throws them together
 * ({@link #throwIfAny()}). Required strings are trimmed, must be non-blank and within their max
 * length; optional values are only checked when present.
 */
public final class Violations {

    public static final int NAME_MAX = 100;
    public static final int TYPE_MAX = 100;
    public static final int IDEMPOTENCY_KEY_MAX = 100;
    public static final int REASON_MAX = 500;
    public static final int MESSAGE_MAX = 1000;
    /** spec, result and error details, as compact JSON. */
    public static final int JSON_MAX_BYTES = 32 * 1024;
    public static final int LABELS_MAX = 16;
    public static final int LABEL_VALUE_MAX = 63;
    public static final int PRIORITY_MAX = 9;
    public static final int MAX_ATTEMPTS_MAX = 20;
    public static final int LEASE_SECONDS_MIN = 5;
    public static final int LEASE_SECONDS_MAX = 3600;
    public static final int CLAIM_TYPES_MAX = 20;
    public static final Duration RUN_AFTER_MAX = Duration.ofDays(30);
    public static final int LIMIT_DEFAULT = 50;
    public static final int LIMIT_MAX = 200;

    // Canonical 8-4-4-4-12 form only: UUID.fromString alone also accepts "1-2-3-4-5".
    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern LABEL_KEY = Pattern.compile("^[a-z0-9]([a-z0-9._/-]{0,61}[a-z0-9])?$");
    private static final Pattern WORKER_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");

    private final List<FieldViolation> violations = new ArrayList<>();

    public void add(String field, String reason) {
        violations.add(FieldViolation.newBuilder().setField(field).setDescription(reason).build());
    }

    public String text(String field, String value, int maxLength) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.indexOf('\0') >= 0) {
            add(field, field + " cannot contain NUL characters");
        } else if (trimmed.isEmpty()) {
            add(field, field + " is required and cannot be blank");
        } else if (trimmed.length() > maxLength) {
            add(field, field + " cannot exceed " + maxLength + " characters");
        }
        return trimmed;
    }

    /** Trimmed; blank means absent (null). */
    public String optionalText(String field, String value, int maxLength) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return text(field, trimmed, maxLength);
    }

    /** A required name that must be one of {@code registered} (the job type registry). */
    public String registeredType(String field, String value, Collection<String> registered) {
        int before = violations.size();
        String trimmed = text(field, value, TYPE_MAX);
        if (violations.size() == before && !registered.contains(trimmed)) {
            add(field, "type '" + trimmed + "' is not a registered job type");
        }
        return trimmed;
    }

    public UUID uuid(String field, String value) {
        if (value == null || !UUID_FORMAT.matcher(value).matches()) {
            add(field, field + " must be a valid UUID");
            return null;
        }
        return UUID.fromString(value);
    }

    public String workerId(String field, String value) {
        if (value == null || !WORKER_ID.matcher(value).matches()) {
            add(field, field + " must be 1-100 characters of A-Z, a-z, 0-9, '.', '_', ':' or '-'");
        }
        return value;
    }

    /** Within [min, max] when present, otherwise {@code fallback} (which may be null). */
    public Integer range(String field, boolean present, int value, int min, int max, Integer fallback) {
        if (!present) {
            return fallback;
        }
        if (value < min || value > max) {
            add(field, field + " must be between " + min + " and " + max);
        }
        return value;
    }

    public int limit(String field, boolean present, int value) {
        return range(field, present, value, 1, LIMIT_MAX, LIMIT_DEFAULT);
    }

    public int offset(String field, boolean present, int value) {
        if (present && value < 0) {
            add(field, field + " must be 0 or greater");
        }
        return present ? value : 0;
    }

    /** Null when absent; must not be more than {@link #RUN_AFTER_MAX} ahead of {@code now}. */
    public Instant runAfter(String field, boolean present, Timestamp value, Instant now) {
        if (!present) {
            return null;
        }
        if (value.getNanos() < 0 || value.getNanos() > 999_999_999
                || value.getSeconds() < Instant.parse("0001-01-01T00:00:00Z").getEpochSecond()
                || value.getSeconds() > Instant.parse("9999-12-31T23:59:59Z").getEpochSecond()) {
            add(field, field + " must be a valid timestamp");
            return null;
        }
        Instant instant = Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
        if (instant.isAfter(now.plus(RUN_AFTER_MAX))) {
            add(field, field + " cannot be more than " + RUN_AFTER_MAX.toDays() + " days ahead");
        }
        return instant;
    }

    public void labels(String field, Map<String, String> labels) {
        if (labels.size() > LABELS_MAX) {
            add(field, field + " cannot have more than " + LABELS_MAX + " entries");
            return;
        }
        labels.forEach((key, value) -> {
            if (!LABEL_KEY.matcher(key).matches()) {
                add(field + "." + key, "label keys must be 1-63 characters of a-z, 0-9, '.', '_', '/' or '-', "
                        + "starting and ending with a letter or digit");
            } else if (value.indexOf('\0') >= 0) {
                add(field + "." + key, "label values cannot contain NUL characters");
            } else if (value.length() > LABEL_VALUE_MAX) {
                add(field + "." + key, "label values cannot exceed " + LABEL_VALUE_MAX + " characters");
            }
        });
    }

    /**
     * Compact JSON of a Struct that PostgreSQL's JSONB can store (no NUL, no NaN/Infinity) and that
     * fits {@link #JSON_MAX_BYTES}; null if it doesn't.
     */
    public String json(String field, Struct value) {
        String problem = unstorable(value);
        if (problem != null) {
            add(field, field + " " + problem);
            return null;
        }
        String json = Json.print(value);
        if (json.getBytes(StandardCharsets.UTF_8).length > JSON_MAX_BYTES) {
            add(field, field + " cannot exceed " + JSON_MAX_BYTES + " bytes as JSON");
            return null;
        }
        return json;
    }

    private static String unstorable(Struct struct) {
        for (var entry : struct.getFieldsMap().entrySet()) {
            String problem = entry.getKey().indexOf('\0') >= 0 ? "cannot contain NUL characters" : unstorable(entry.getValue());
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private static String unstorable(Value value) {
        return switch (value.getKindCase()) {
            case STRING_VALUE -> value.getStringValue().indexOf('\0') >= 0 ? "cannot contain NUL characters" : null;
            case NUMBER_VALUE -> Double.isFinite(value.getNumberValue()) ? null : "cannot contain NaN or Infinity";
            case STRUCT_VALUE -> unstorable(value.getStructValue());
            case LIST_VALUE -> unstorable(value.getListValue());
            default -> null;
        };
    }

    private static String unstorable(ListValue list) {
        for (Value item : list.getValuesList()) {
            String problem = unstorable(item);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    public void throwIfAny() {
        if (!violations.isEmpty()) {
            throw new ApiException.BadInput(violations);
        }
    }
}
