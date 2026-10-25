package com.gathin.tvhome;

/** Small input rule shared by the TV dialog and JVM tests. */
final class DpadSequencePolicy {
    private static final int ACTION_DOWN = 0;

    private DpadSequencePolicy() { }

    static boolean acceptsPress(int action, int repeatCount) {
        return action == ACTION_DOWN && repeatCount == 0;
    }
}
