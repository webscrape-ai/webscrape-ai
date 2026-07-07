package ai.webscrape.sdk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * SmartBrowse run lifecycle state. Unknown-tolerant: any wire value the SDK does not
 * recognize deserializes to {@link #UNKNOWN} rather than failing, so new server states
 * can ship without an SDK release.
 */
public enum RunStatus {
    QUEUED("queued"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    /** A value not known to this SDK version. */
    UNKNOWN("unknown");

    private final String wire;

    RunStatus(String wire) {
        this.wire = wire;
    }

    /** The wire string for this status. */
    @JsonValue
    public String wire() {
        return wire;
    }

    /** {@code true} for {@link #COMPLETED}, {@link #FAILED}, {@link #CANCELLED}. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** Map a wire string to a status, returning {@link #UNKNOWN} for anything unrecognized. */
    @JsonCreator
    public static RunStatus fromWire(String value) {
        if (value != null) {
            for (RunStatus status : values()) {
                if (status.wire.equalsIgnoreCase(value)) {
                    return status;
                }
            }
        }
        return UNKNOWN;
    }
}
