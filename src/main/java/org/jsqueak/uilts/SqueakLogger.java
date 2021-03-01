package org.jsqueak.uilts;

import org.jsqueak.SqueakConfig;

public class SqueakLogger {

    public static final String LOG_BLOCK_HEADER = " => +----------";
    public static final String LOG_BLOCK_ENDDER = "    +----------";

    public static void log_D(String msg) {
        if (SqueakConfig.DEBUG_LOGGING) {
            System.out.println(msg);
        }
    }

    public static void log_E(String msg) {
        if (SqueakConfig.DEBUG_LOGGING) {
            System.err.println(msg);
        }
    }
}
