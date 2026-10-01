package com.pocketai.installcheck;

import org.junit.Test;
import static org.junit.Assert.*;

public class InstallResultTest {
    @Test public void exactPlatformErrorIsPreserved() {
        String result = InstallResult.format(5, -7,
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match");
        assertTrue(result.contains("Conflit"));
        assertTrue(result.contains("STATUS = 5"));
        assertTrue(result.contains("LEGACY_STATUS = -7"));
        assertTrue(result.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"));
    }
    @Test public void missingLegacyStatusIsNotInvented() {
        assertTrue(InstallResult.format(1, Integer.MIN_VALUE, null)
                .contains("LEGACY_STATUS = non fourni"));
    }
    @Test public void successAndCancellationAreDistinct() {
        assertTrue(InstallResult.format(0, 1, null).startsWith("Installation réussie"));
        assertTrue(InstallResult.format(3, -115, null).startsWith("Installation annulée"));
    }
    @Test public void unknownVendorStatusAndLongMessagesAreSafe() {
        String result = InstallResult.format(200, -900, "x".repeat(20_000));
        assertTrue(result.contains("STATUS = 200"));
        assertTrue(result.length() < 12_200);
    }
}
