package com.gathin.tvhome;

import android.os.Build;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/** Startup-only checks. Does not change properties, permissions, firmware or any TV settings. */
final class NativeHomeClockPrerequisites {
    private static final String FINGERPRINT =
            "Hisense/huangshan/huangshan:10/QTG3.200902.001/00.00.00.101:user/release-keys";
    private static final String FRAMEWORK_SHA256 =
            "012114a66aec3184405d29588b4f472a24b009ab516c7a3dc6fb0cef50209e7d";
    private static boolean frameworkVerified;
    private NativeHomeClockPrerequisites() { }

    static synchronized String problem() {
        if (Build.VERSION.SDK_INT != 29 || !FINGERPRINT.equals(Build.FINGERPRINT))
            return "firmware_mismatch";
        try {
            if (!frameworkVerified) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = new FileInputStream("/system/framework/services.jar")) {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
                }
                StringBuilder hex = new StringBuilder();
                for (byte value : digest.digest()) hex.append(String.format("%02x", value & 255));
                if (!FRAMEWORK_SHA256.equals(hex.toString())) return "firmware_mismatch";
                frameworkVerified = true; // Immutable system partition; cache only in this process.
            }
            Process process = new ProcessBuilder("/system/bin/getprop",
                    "persist.debug.time_correction").redirectErrorStream(true).start();
            try {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) return "property_unverified";
                byte[] result = new byte[128];
                int count = process.getInputStream().read(result);
                String value = count < 0 ? "" : new String(result, 0, count,
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                // Empty corroborates the host-verified absent/default-true configuration; it does
                // not distinguish an absent property from one filtered by the app's SELinux view.
                return process.exitValue() == 0 && (value.isEmpty() || "true".equals(value))
                        ? null : "property_unverified";
            } finally { process.destroy(); }
        } catch (Exception unavailable) { return "property_unverified"; }
    }
}
