package com.springtime.bigquery.contract;

/** API version constants. The path is authoritative; the response header is informational. */
public final class ApiVersion {

    public static final String CURRENT = "1";
    public static final String CONTRACT_VERSION = "1.0.0";
    public static final String HEADER = "X-API-Version";

    private ApiVersion() {
    }
}
