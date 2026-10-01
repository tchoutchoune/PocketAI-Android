package com.pocketai.installcheck;

/** Formatting kept independent of Android so the actual result values remain testable. */
final class InstallResult {
    private InstallResult() {}

    static String format(int status, int legacyStatus, String message) {
        String label;
        switch (status) {
            case -1: label = "Confirmation Android nécessaire"; break;
            case 0: label = "Installation réussie"; break;
            case 1: label = "Installation refusée"; break;
            case 2: label = "Installation bloquée"; break;
            case 3: label = "Installation annulée"; break;
            case 4: label = "APK considéré invalide"; break;
            case 5: label = "Conflit avec une application installée"; break;
            case 6: label = "Espace insuffisant pour l'installation"; break;
            case 7: label = "APK incompatible avec cet appareil"; break;
            case 8: label = "Délai d'installation dépassé"; break;
            default: label = "Résultat Android";
        }
        String detail = message == null || message.isBlank() ? "Non fourni par Android" : message;
        if (detail.length() > 12_000) detail = detail.substring(0, 12_000);
        return label + "\nSTATUS = " + status + "\nLEGACY_STATUS = "
                + (legacyStatus == Integer.MIN_VALUE ? "non fourni" : legacyStatus)
                + "\nSTATUS_MESSAGE = " + detail;
    }
}
