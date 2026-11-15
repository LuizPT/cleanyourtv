package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class FamilyScreenTransitionPolicyTest {
    @Test public void ignoresOnWithoutObservedWakeAndOffWhileDisplayIsInteractive() {
        FamilyScreenTransitionPolicy policy = new FamilyScreenTransitionPolicy();
        assertEquals(FamilyScreenTransitionPolicy.Transition.IGNORE,
                policy.accept(true, true, false));
        assertEquals(FamilyScreenTransitionPolicy.Transition.IGNORE,
                policy.accept(false, true, false));
    }

    @Test public void acceptsOneRealOffOnPairAndIgnoresDuplicates() {
        FamilyScreenTransitionPolicy policy = new FamilyScreenTransitionPolicy();
        assertEquals(FamilyScreenTransitionPolicy.Transition.SCREEN_OFF,
                policy.accept(false, false, false));
        assertEquals(FamilyScreenTransitionPolicy.Transition.IGNORE,
                policy.accept(false, false, false));
        assertEquals(FamilyScreenTransitionPolicy.Transition.IGNORE,
                policy.accept(true, false, true));
        assertEquals(FamilyScreenTransitionPolicy.Transition.SCREEN_ON,
                policy.accept(true, true, true));
        assertEquals(FamilyScreenTransitionPolicy.Transition.IGNORE,
                policy.accept(true, true, true));
    }

    @Test public void acceptsPersistedScreenOffWhenTheOffBroadcastWasMissed() {
        FamilyScreenTransitionPolicy policy = new FamilyScreenTransitionPolicy();
        assertEquals(FamilyScreenTransitionPolicy.Transition.SCREEN_ON,
                policy.accept(true, true, true));
        assertEquals(FamilyScreenTransitionPolicy.Transition.IGNORE,
                policy.accept(true, true, true));
    }

    @Test public void delayedOffAfterWakeIsIgnoredAgainstInteractiveState() {
        FamilyScreenTransitionPolicy policy = new FamilyScreenTransitionPolicy();
        assertEquals(FamilyScreenTransitionPolicy.Transition.SCREEN_OFF,
                policy.accept(false, false, false));
        assertEquals(FamilyScreenTransitionPolicy.Transition.SCREEN_ON,
                policy.accept(true, true, false));
        assertEquals(FamilyScreenTransitionPolicy.Transition.IGNORE,
                policy.accept(false, true, false));
    }

    @Test public void activityAndServiceTrackTheSameRealOffWakePairIndependently() {
        FamilyScreenTransitionPolicy activityReceiver = new FamilyScreenTransitionPolicy();
        FamilyScreenTransitionPolicy serviceReceiver = new FamilyScreenTransitionPolicy();
        for (FamilyScreenTransitionPolicy policy : new FamilyScreenTransitionPolicy[] {
                activityReceiver, serviceReceiver }) {
            assertEquals(FamilyScreenTransitionPolicy.Transition.SCREEN_OFF,
                    policy.accept(false, false, false));
            assertEquals(FamilyScreenTransitionPolicy.Transition.IGNORE,
                    policy.accept(false, false, true));
        }
        for (FamilyScreenTransitionPolicy policy : new FamilyScreenTransitionPolicy[] {
                activityReceiver, serviceReceiver }) {
            assertEquals(FamilyScreenTransitionPolicy.Transition.SCREEN_ON,
                    policy.accept(true, true, true));
            assertEquals(FamilyScreenTransitionPolicy.Transition.IGNORE,
                    policy.accept(true, true, true));
            assertEquals(FamilyScreenTransitionPolicy.Transition.IGNORE,
                    policy.accept(false, true, false));
        }
    }
}
