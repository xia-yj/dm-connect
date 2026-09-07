package com.dmconnect.connection;

import java.time.Duration;

/** Shared connect-handshake budget for JDBC and native clients. */
public final class ConnectTimeouts {
    public static final int SECONDS = 30;
    public static final int MILLIS = SECONDS * 1_000;
    public static final Duration CONNECT = Duration.ofSeconds(SECONDS);

    private ConnectTimeouts() {
    }
}
