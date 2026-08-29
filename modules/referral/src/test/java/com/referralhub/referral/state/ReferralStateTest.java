package com.referralhub.referral.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every edge of the lifecycle graph, present and absent.
 *
 * <p>Enumerating all 36 ordered pairs rather than spot-checking a few: the transitions that
 * matter are the ones nobody thought of, and "SUBMITTED without ACCEPTED" is exactly the kind of
 * move that a controller-level check misses when a second entry point is added later.
 */
class ReferralStateTest {

    @ParameterizedTest(name = "{0} -> {1} is legal")
    @DisplayName("the happy path and both exit paths are legal")
    @CsvSource({
            "REQUESTED, ACCEPTED",
            "REQUESTED, DECLINED",
            "REQUESTED, EXPIRED",
            "ACCEPTED,  SUBMITTED",
            "ACCEPTED,  EXPIRED",
            "ACCEPTED,  CLOSED",
            "SUBMITTED, CLOSED"
    })
    void legalTransitions(ReferralState from, ReferralState to) {
        assertThat(from.canTransitionTo(to)).isTrue();
        assertThat(from.transitionTo(to)).isEqualTo(to);
    }

    static List<org.junit.jupiter.params.provider.Arguments> allPairs() {
        List<org.junit.jupiter.params.provider.Arguments> pairs = new ArrayList<>();
        for (ReferralState from : ReferralState.values()) {
            for (ReferralState to : ReferralState.values()) {
                if (!from.canTransitionTo(to)) {
                    pairs.add(org.junit.jupiter.params.provider.Arguments.of(from, to));
                }
            }
        }
        return pairs;
    }

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @DisplayName("every transition not on the graph throws, including self-transitions")
    @MethodSource("allPairs")
    void illegalTransitionsThrow(ReferralState from, ReferralState to) {
        assertThatThrownBy(() -> from.transitionTo(to))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining(from.name())
                .hasMessageContaining(to.name());
    }

    @Test
    @DisplayName("a referral cannot be submitted without first being accepted")
    void cannotSubmitWithoutAccepting() {
        assertThat(ReferralState.REQUESTED.canTransitionTo(ReferralState.SUBMITTED)).isFalse();
    }

    @Test
    @DisplayName("a declined or expired referral is final and cannot be revived")
    void terminalStatesAreFinal() {
        for (ReferralState terminal : List.of(ReferralState.CLOSED, ReferralState.DECLINED,
                ReferralState.EXPIRED)) {
            assertThat(terminal.isTerminal()).isTrue();
            assertThat(terminal.allowedNext()).isEmpty();
        }
    }

    @ParameterizedTest
    @DisplayName("non-terminal states always have somewhere to go, so nothing can get stuck")
    @EnumSource(value = ReferralState.class,
            names = {"REQUESTED", "ACCEPTED", "SUBMITTED"})
    void nonTerminalStatesHaveAnExit(ReferralState state) {
        assertThat(state.isTerminal()).isFalse();
        assertThat(state.allowedNext()).isNotEmpty();
    }

    @Test
    @DisplayName("the resume is released exactly in the states where a referrer is acting on it")
    void resumeAccessIsScopedToActiveStates() {
        assertThat(ReferralState.ACCEPTED.grantsResumeAccess()).isTrue();
        assertThat(ReferralState.SUBMITTED.grantsResumeAccess()).isTrue();

        // Before acceptance nobody has committed; after closure the reason to hold it is gone.
        assertThat(ReferralState.REQUESTED.grantsResumeAccess()).isFalse();
        assertThat(ReferralState.DECLINED.grantsResumeAccess()).isFalse();
        assertThat(ReferralState.EXPIRED.grantsResumeAccess()).isFalse();
        assertThat(ReferralState.CLOSED.grantsResumeAccess()).isFalse();
    }

    @Test
    @DisplayName("the exception names the moves that were available instead")
    void exceptionIsActionable() {
        IllegalTransitionException exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> ReferralState.SUBMITTED.transitionTo(ReferralState.ACCEPTED),
                IllegalTransitionException.class);

        assertThat(exception.from()).isEqualTo(ReferralState.SUBMITTED);
        assertThat(exception.to()).isEqualTo(ReferralState.ACCEPTED);
        assertThat(exception.getMessage()).contains("CLOSED");
        assertThat(exception.code()).isEqualTo("illegal_transition");
    }
}
