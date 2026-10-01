package com.pocketai.installcheck;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final String EXPECTED_PACKAGE = "com.pocketai.app";
    // This framework action is used in Android's supplied confirmation intent.
    static final String CONFIRM_ACTION = "android.content.pm.action.CONFIRM_INSTALL";
    private static final int PICK_APK = 501;
    private static final long MAX_APK_BYTES = 128L * 1024 * 1024;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean CANCEL = new AtomicBoolean();
    private static volatile boolean working;
    private static WeakReference<MainActivity> active = new WeakReference<>(null);
    private boolean resumed;
    private TextView metadata, result;
    private Button choose, install, cancel;

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("installation", MODE_PRIVATE);
    }

    static void statusArrived() {
        MAIN.post(() -> {
            MainActivity activity = active.get();
            if (activity != null && !activity.isDestroyed()) {
                activity.render();
                if (activity.resumed) activity.confirmInstallation();
            }
        });
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        active = new WeakReference<>(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, padding, padding, padding);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(padding + bars.left, padding + bars.top,
                    padding + bars.right, padding + bars.bottom);
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        setContentView(scroll);
        TextView title = text("Diagnostic d'installation PocketAI", 24);
        content.addView(title);
        content.addView(text("Révèle la raison fournie par Android quand PocketAI 4 ne s'installe pas. "
                + "Sélectionne son APK téléchargé, puis lance une installation normale. "
                + "Android demandera ta confirmation. Aucun fichier n'est envoyé sur Internet.", 16));
        choose = button("1. Choisir l'APK PocketAI 4", view -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(intent, PICK_APK);
        });
        content.addView(choose);
        metadata = text("", 14);
        metadata.setTextIsSelectable(true);
        content.addView(metadata);
        install = button("2. Installer et obtenir le résultat", view -> installExplicitly());
        content.addView(install);
        cancel = button("Annuler la préparation", view -> CANCEL.set(true));
        content.addView(cancel);
        result = text("", 16);
        result.setTextIsSelectable(true);
        content.addView(result);
        content.addView(button("Copier le diagnostic", view -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostic PocketAI",
                    metadata.getText() + "\n\n" + result.getText()));
            Toast.makeText(this, "Diagnostic copié", Toast.LENGTH_SHORT).show();
        }));
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        active = new WeakReference<>(this);
        render();
        if (prefs(this).getBoolean("awaiting_permission", false)) {
            prefs(this).edit().remove("awaiting_permission").apply();
            if (getPackageManager().canRequestPackageInstalls()) prepareInstallation();
            else showResult("Autorisation non accordée. Tu peux relancer l'installation "
                    + "après avoir autorisé cette application comme source.");
        }
    }

    @Override protected void onPause() { resumed = false; super.onPause(); }

    @Override protected void onDestroy() {
        if (isFinishing() && working) CANCEL.set(true);
        if (active.get() == this) active.clear();
        super.onDestroy();
    }

    @Override protected void onActivityResult(int request, int response, Intent data) {
        super.onActivityResult(request, response, data);
        if (request != PICK_APK || response != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Context context = getApplicationContext();
        beginWork("Lecture et vérification de l'APK…");
        int previous = prefs(context).getInt("session", -1);
        prefs(context).edit().remove("metadata").remove("confirmation").putInt("session", -1).apply();
        IO.execute(() -> {
            File temporary = new File(context.getCacheDir(), "selected.apk.tmp");
            File selected = selectedFile(context);
            try {
                if (previous >= 0) {
                    try { context.getPackageManager().getPackageInstaller().abandonSession(previous); }
                    catch (Exception ignored) { }
                }
                selected.delete();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = context.getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    if (input == null) throw new Exception("Impossible de lire le fichier sélectionné.");
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0;
                    for (int read; (read = input.read(buffer)) != -1;) {
                        checkCancellation();
                        total += read;
                        if (total > MAX_APK_BYTES) throw new Exception("APK trop volumineux : limite 128 Mo.");
                        digest.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                    }
                    output.getFD().sync();
                }
                checkCancellation();
                PackageInfo info = context.getPackageManager().getPackageArchiveInfo(
                        temporary.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
                if (info == null) throw new Exception("Android ne parvient pas à analyser cet APK.");
                if (!EXPECTED_PACKAGE.equals(info.packageName))
                    throw new Exception("Ce fichier n'est pas l'APK PocketAI 4 attendu (com.pocketai.app).");
                String details = "Appareil : " + Build.MANUFACTURER + " " + Build.MODEL
                        + " · Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
                        + "\nAPK : " + info.packageName + " · " + info.versionName
                        + " (" + info.getLongVersionCode() + ")"
                        + "\nTaille : " + temporary.length() + " octets"
                        + "\nSHA-256 APK : " + hex(digest.digest())
                        + "\nSHA-256 signature : " + signer(info)
                        + "\n" + installedPackage(context);
                if (!temporary.renameTo(selected)) throw new Exception("Impossible de conserver l'APK sélectionné.");
                prefs(context).edit().putString("metadata", details)
                        .putString("result", "APK prêt. Lance l'installation pour obtenir le code de résultat Android.").apply();
            } catch (Exception error) {
                showResult(context, error.getMessage());
            } finally {
                temporary.delete();
                finishWork();
            }
        });
    }

    private void installExplicitly() {
        if (working) return;
        if (!prefs(this).getString("confirmation", "").isEmpty()) {
            confirmInstallation();
            return;
        }
        if (!selectedFile(this).isFile()) { showResult("Choisis d'abord l'APK PocketAI 4."); return; }
        if (!getPackageManager().canRequestPackageInstalls()) {
            prefs(this).edit().putBoolean("awaiting_permission", true).apply();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception error) {
                prefs(this).edit().remove("awaiting_permission").apply();
                showResult("Ouvre les paramètres de cette application et autorise "
                        + "« Installer des applications inconnues », puis réessaie.");
            }
            return;
        }
        prepareInstallation();
    }

    private void prepareInstallation() {
        if (working || !selectedFile(this).isFile()) return;
        Context context = getApplicationContext();
        beginWork("Préparation de la demande d'installation Android…");
        IO.execute(() -> {
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            int sessionId = -1;
            boolean committed = false;
            try {
                // A retry abandons only this diagnostic application's own earlier session.
                int previous = prefs(context).getInt("session", -1);
                if (previous >= 0) {
                    try { installer.abandonSession(previous); } catch (Exception ignored) { }
                }
                File apk = selectedFile(context);
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(EXPECTED_PACKAGE);
                params.setSize(apk.length());
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE);
                sessionId = installer.createSession(params);
                prefs(context).edit().putInt("session", sessionId).remove("confirmation").apply();
                try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                    try (InputStream input = new FileInputStream(apk);
                         OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                        byte[] buffer = new byte[64 * 1024];
                        for (int read; (read = input.read(buffer)) != -1;) {
                            checkCancellation();
                            output.write(buffer, 0, read);
                        }
                        session.fsync(output);
                    }
                    // Every openWrite stream must be closed before commit.
                    checkCancellation();
                    Intent status = new Intent(context, InstallStatusReceiver.class)
                            .setAction(InstallStatusReceiver.ACTION);
                    PendingIntent callback = PendingIntent.getBroadcast(context, sessionId, status,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    // Android fills status extras into an explicit, nonexported receiver.
                    session.commit(callback.getIntentSender());
                    committed = true;
                }
            } catch (Exception error) {
                showResult(context, "Préparation interrompue : " + error.getClass().getSimpleName()
                        + "\n" + (error.getMessage() == null ? "Aucun détail fourni." : error.getMessage()));
            } finally {
                if (!committed && sessionId >= 0) {
                    try { installer.abandonSession(sessionId); } catch (Exception ignored) { }
                    prefs(context).edit().putInt("session", -1).apply();
                }
                finishWork();
            }
        });
    }

    private void confirmInstallation() {
        if (!resumed) return;
        String stored = prefs(this).getString("confirmation", "");
        if (stored.isEmpty()) return;
        try {
            int sessionId = prefs(this).getInt("session", -1);
            PackageInstaller.SessionInfo session = getPackageManager().getPackageInstaller().getSessionInfo(sessionId);
            if (session == null || !getPackageName().equals(session.getInstallerPackageName()))
                throw new Exception("Cette demande Android n'est plus disponible. Relance l'installation.");
            Intent intent = Intent.parseUri(stored, Intent.URI_INTENT_SCHEME);
            if (!CONFIRM_ACTION.equals(intent.getAction()))
                throw new Exception("Demande de confirmation Android invalide.");
            prefs(this).edit().remove("confirmation").apply();
            startActivity(intent);
        } catch (Exception error) {
            prefs(this).edit().remove("confirmation").apply();
            showResult("Impossible d'ouvrir la confirmation Android : " + error.getMessage());
        }
    }

    private void render() {
        SharedPreferences preferences = prefs(this);
        metadata.setText(preferences.getString("metadata", "Aucun APK sélectionné."));
        result.setText(preferences.getString("result", "Le résultat détaillé s'affichera ici."));
        choose.setEnabled(!working);
        install.setEnabled(!working && (selectedFile(this).isFile()
                || !preferences.getString("confirmation", "").isEmpty()));
        install.setText(preferences.getString("confirmation", "").isEmpty()
                ? "2. Installer et obtenir le résultat" : "Confirmer l'installation avec Android");
        cancel.setVisibility(working ? View.VISIBLE : View.GONE);
    }

    private void beginWork(String message) {
        CANCEL.set(false);
        working = true;
        showResult(message);
    }
    private static void finishWork() { working = false; statusArrived(); }
    private static void checkCancellation() throws Exception {
        if (CANCEL.get()) throw new Exception("Préparation annulée.");
    }
    private void showResult(String message) { showResult(this, message); }
    private static void showResult(Context context, String message) {
        prefs(context).edit().putString("result", message == null ? "Erreur sans détail fourni." : message).apply();
        statusArrived();
    }
    private static File selectedFile(Context context) { return new File(context.getCacheDir(), "selected.apk"); }
    private static String installedPackage(Context context) {
        try {
            PackageInfo installed = context.getPackageManager().getPackageInfo(EXPECTED_PACKAGE,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES));
            return "PocketAI 4 déjà installée : " + installed.versionName + " ("
                    + installed.getLongVersionCode() + ")\nSHA-256 signature installée : " + signer(installed);
        } catch (PackageManager.NameNotFoundException missing) {
            return "PocketAI 4 : non installée pour cet utilisateur Android.";
        } catch (Exception error) { return "Version installée : information indisponible."; }
    }
    private static String signer(PackageInfo info) throws Exception {
        if (info.signingInfo == null) return "indisponible";
        Signature[] signatures = info.signingInfo.getApkContentsSigners();
        if (signatures == null || signatures.length == 0) return "indisponible";
        return hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
    }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return text.toString();
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, dp(8), 0, dp(12));
        return view;
    }
    private Button button(String label, View.OnClickListener action) {
        Button view = new Button(this);
        view.setText(label);
        view.setMinHeight(dp(48));
        view.setAllCaps(false);
        view.setOnClickListener(action);
        return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
