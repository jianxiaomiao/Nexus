package com.nexus.auth.service;

import com.nexus.auth.token.IssuedAccessToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RefreshOutcomeTests {

    private static final IssuedAccessToken ACCESS_TOKEN =
            new IssuedAccessToken("access-token-for-outcome-test", 600);
    private static final String REFRESH_TOKEN = "refresh-token-for-outcome-test";

    @Test
    void successShouldCarryBothTokensAndTheActualRemainingLifetime() {
        RefreshOutcome outcome = RefreshOutcome.success(ACCESS_TOKEN, REFRESH_TOKEN);

        assertAll(
                () -> assertEquals(RefreshStatus.SUCCESS, outcome.status()),
                () -> assertEquals(ACCESS_TOKEN, outcome.accessToken()),
                () -> assertEquals(600, outcome.accessToken().expiresInSeconds()),
                () -> assertEquals(REFRESH_TOKEN, outcome.refreshToken())
        );
    }

    @Test
    void failureFactoriesShouldPreserveTheReasonWithoutCredentials() {
        RefreshOutcome[] outcomes = {
                RefreshOutcome.invalid(),
                RefreshOutcome.accountForbidden(),
                RefreshOutcome.replayRevoked()
        };
        RefreshStatus[] expectedStatuses = {
                RefreshStatus.INVALID,
                RefreshStatus.ACCOUNT_FORBIDDEN,
                RefreshStatus.REPLAY_REVOKED
        };

        for (int index = 0; index < outcomes.length; index++) {
            RefreshOutcome outcome = outcomes[index];
            assertEquals(expectedStatuses[index], outcome.status());
            assertNull(outcome.accessToken());
            assertNull(outcome.refreshToken());
        }
    }

    @Test
    void successShouldRejectMissingOrBlankAccessTokens() {
        IssuedAccessToken[] invalidTokens = {
                null,
                new IssuedAccessToken(null, 600),
                new IssuedAccessToken("", 600),
                new IssuedAccessToken(" \t\n", 600)
        };

        for (IssuedAccessToken invalidToken : invalidTokens) {
            assertThrows(IllegalArgumentException.class,
                    () -> RefreshOutcome.success(invalidToken, REFRESH_TOKEN));
        }
    }

    @Test
    void successShouldRejectNonPositiveAccessTokenLifetimes() {
        for (long expiresInSeconds : new long[]{0, -1}) {
            IssuedAccessToken invalidToken =
                    new IssuedAccessToken(ACCESS_TOKEN.value(), expiresInSeconds);

            assertThrows(IllegalArgumentException.class,
                    () -> RefreshOutcome.success(invalidToken, REFRESH_TOKEN));
        }
    }

    @Test
    void successShouldRejectMissingOrBlankRefreshTokens() {
        for (String invalidToken : new String[]{null, "", " \t\n"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> RefreshOutcome.success(ACCESS_TOKEN, invalidToken));
        }
    }

    @Test
    void directConstructionShouldRejectCredentialsOnEveryFailureStatus() {
        for (RefreshStatus status : RefreshStatus.values()) {
            if (status == RefreshStatus.SUCCESS) {
                continue;
            }

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new RefreshOutcome(status, ACCESS_TOKEN, null)),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new RefreshOutcome(status, null, REFRESH_TOKEN)),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new RefreshOutcome(status, ACCESS_TOKEN, REFRESH_TOKEN))
            );
        }
    }

    @Test
    void directConstructionShouldRejectAMissingStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> new RefreshOutcome(null, null, null));
    }

    @Test
    void diagnosticTextShouldNeverIncludeTokenValues() {
        RefreshOutcome success = RefreshOutcome.success(ACCESS_TOKEN, REFRESH_TOKEN);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new RefreshOutcome(RefreshStatus.INVALID, ACCESS_TOKEN, REFRESH_TOKEN));

        for (String diagnosticText : new String[]{success.toString(), failure.toString()}) {
            assertFalse(diagnosticText.contains(ACCESS_TOKEN.value()));
            assertFalse(diagnosticText.contains(REFRESH_TOKEN));
        }
        assertTrue(success.toString().contains(RefreshStatus.SUCCESS.name()));
    }
}
