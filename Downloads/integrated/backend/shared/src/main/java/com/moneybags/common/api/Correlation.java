package com.moneybags.common.api;
import org.slf4j.MDC;
import java.util.UUID;
public final class Correlation {
    public static final String HEADER="X-Correlation-ID";
    private Correlation() {}
    public static String current() { String id=MDC.get("correlationId");return id==null?UUID.randomUUID().toString():id; }
}
