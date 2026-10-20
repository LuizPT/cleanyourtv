package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small, testable action model shared by Home tiles and the compact app library. */
final class AppActionPolicy {
    enum Action { MOVE, HIDE, SHOW, PROTECT, UNPROTECT, APP_INFO, ADD_TO_HOME, REMOVE_FROM_HOME }

    private AppActionPolicy() { }

    static List<Action> contextActions(boolean inLibrary, boolean hidden, boolean protectedApp, boolean pinned) {
        List<Action> actions = new ArrayList<>();
        if (!inLibrary || pinned) actions.add(Action.MOVE);
        if (inLibrary) actions.add(pinned ? Action.REMOVE_FROM_HOME : Action.ADD_TO_HOME);
        actions.add(hidden ? Action.SHOW : Action.HIDE);
        actions.add(protectedApp ? Action.UNPROTECT : Action.PROTECT);
        actions.add(Action.APP_INFO);
        return Collections.unmodifiableList(actions);
    }

    /** The visibility state and parental state remain independent. */
    static List<Status> statuses(boolean hidden, boolean protectedApp) {
        List<Status> status = new ArrayList<>();
        status.add(hidden ? Status.HIDDEN : Status.ON_HOME);
        if (protectedApp) status.add(Status.PROTECTED);
        return Collections.unmodifiableList(status);
    }

    enum Status { ON_HOME, HIDDEN, PROTECTED }
}
