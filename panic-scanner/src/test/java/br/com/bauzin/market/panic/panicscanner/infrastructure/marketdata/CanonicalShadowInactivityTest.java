package br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalShadowInactivityTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void inactiveBeforeComparisonButComparableSessionSurvivesLastPauseAndCanResume() {
        assertTrue(CanonicalLiveShadowDiagnostics.inactivityEndsSession(
                true, -1, 30 * SECOND + 1, 0));

        long comparisonBegan = SECOND;
        long lastProgress = 992 * SECOND;
        // Same condition as the reported run: ~1022 seconds, no LAST for >30s.
        assertFalse(CanonicalLiveShadowDiagnostics.inactivityEndsSession(
                true, comparisonBegan, 1022 * SECOND + 1, lastProgress));
        assertFalse(CanonicalLiveShadowDiagnostics.inactivityEndsSession(
                true, comparisonBegan, 1100 * SECOND, lastProgress));
        lastProgress = 1101 * SECOND; // LAST resumes; the session was not terminated.
        assertFalse(CanonicalLiveShadowDiagnostics.inactivityEndsSession(
                true, comparisonBegan, 1101 * SECOND, lastProgress));
    }

    @Test
    void startupStillRequiresActivityWithOriginalStrictThirtySecondBoundary() {
        assertFalse(CanonicalLiveShadowDiagnostics.inactivityEndsSession(true, -1, 30 * SECOND, 0));
        assertTrue(CanonicalLiveShadowDiagnostics.inactivityEndsSession(true, -1, 30 * SECOND + 1, 0));
        assertFalse(CanonicalLiveShadowDiagnostics.inactivityEndsSession(false, -1, 100 * SECOND, 0));
    }

    @Test
    void comparisonStartingAtZeroAlsoSurvivesSilence() {
        assertFalse(CanonicalLiveShadowDiagnostics.inactivityEndsSession(true, 0, 31 * SECOND, 0));
    }

    @Test
    void durationAllowsOneAdditionalM5BucketForSixCompleteSessionBuckets() {
        long comparisonBegan = 0;
        long thirtyMinutes = 30 * 60 * SECOND;
        long thirtyFiveMinutes = 35 * 60 * SECOND;

        assertFalse(CanonicalLiveShadowDiagnostics.durationEndsSession(
                comparisonBegan, thirtyMinutes, 30, 5));
        assertTrue(CanonicalLiveShadowDiagnostics.durationEndsSession(
                comparisonBegan, thirtyMinutes, 30, 6));
        assertTrue(CanonicalLiveShadowDiagnostics.durationEndsSession(
                comparisonBegan, thirtyFiveMinutes, 30, 5));
        assertTrue(CanonicalLiveShadowDiagnostics.durationEndsSession(
                comparisonBegan, thirtyFiveMinutes, 30, 6));
    }
}
