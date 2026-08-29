package com.referralhub.referral.property;

import static org.assertj.core.api.Assertions.assertThat;

import com.referralhub.referral.state.IllegalTransitionException;
import com.referralhub.referral.state.ReferralState;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

/**
 * Invariants of the lifecycle graph, checked over generated walks rather than chosen examples.
 *
 * <p>{@code ReferralStateTest} enumerates all 36 ordered pairs, which proves the edges are right.
 * These properties prove things about *sequences* — that no reachable path escapes the graph, and
 * that a terminal state absorbs everything thrown at it. Those are the claims a future edit to
 * the transition table is most likely to break.
 */
class ReferralStateProperties {

    @Provide
    Arbitrary<ReferralState> states() {
        return Arbitraries.of(ReferralState.values());
    }

    @Property(tries = 500)
    void anyAcceptedWalkStaysOnTheGraph(
            @ForAll("states") ReferralState start,
            @ForAll @Size(min = 1, max = 12) List<@net.jqwik.api.constraints.IntRange(min = 0, max = 5) Integer> choices) {

        ReferralState current = start;
        List<ReferralState> visited = new ArrayList<>(List.of(current));

        for (int choice : choices) {
            List<ReferralState> allowed = new ArrayList<>(current.allowedNext());
            if (allowed.isEmpty()) {
                break;
            }
            ReferralState next = allowed.get(choice % allowed.size());
            current = current.transitionTo(next);
            visited.add(current);
        }

        // Every consecutive pair in the walk must be a real edge.
        for (int i = 1; i < visited.size(); i++) {
            assertThat(visited.get(i - 1).canTransitionTo(visited.get(i)))
                    .as("%s -> %s must be a legal edge", visited.get(i - 1), visited.get(i))
                    .isTrue();
        }
    }

    @Property(tries = 200)
    void everyWalkTerminatesWithinTheGraphDiameter(
            @ForAll("states") ReferralState start,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 0, max = 5) int choice) {

        ReferralState current = start;
        int steps = 0;
        while (!current.isTerminal() && steps < 20) {
            List<ReferralState> allowed = new ArrayList<>(current.allowedNext());
            current = current.transitionTo(allowed.get(choice % allowed.size()));
            steps++;
        }
        // The longest path is REQUESTED -> ACCEPTED -> SUBMITTED -> CLOSED. Anything that does
        // not terminate in four steps means a cycle has been introduced.
        assertThat(current.isTerminal()).isTrue();
        assertThat(steps).isLessThanOrEqualTo(3);
    }

    @Property
    void terminalStatesAbsorbEverything(@ForAll("states") ReferralState target) {
        for (ReferralState terminal : List.of(ReferralState.CLOSED, ReferralState.DECLINED,
                ReferralState.EXPIRED)) {
            assertThat(terminal.canTransitionTo(target)).isFalse();
            try {
                terminal.transitionTo(target);
                throw new AssertionError("expected " + terminal + " -> " + target + " to be refused");
            } catch (IllegalTransitionException expected) {
                assertThat(expected.from()).isEqualTo(terminal);
                assertThat(expected.to()).isEqualTo(target);
            }
        }
    }

    @Property
    void noStateEverTransitionsToItself(@ForAll("states") ReferralState state) {
        assertThat(state.canTransitionTo(state)).isFalse();
    }

    @Property
    void resumeAccessIsOnlyEverGrantedInActiveStates(@ForAll("states") ReferralState state) {
        if (state.grantsResumeAccess()) {
            assertThat(state)
                    .as("a resume must never be readable outside an active referral")
                    .isIn(ReferralState.ACCEPTED, ReferralState.SUBMITTED);
        }
    }
}
