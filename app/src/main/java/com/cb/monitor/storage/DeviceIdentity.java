package com.cb.monitor.storage;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class DeviceIdentity {
    private DeviceIdentity() {}

    public static String resolve(Context context) {
        String[] candidates = new String[]{
                readSystemSerial(),
                readBuildSerial(),
                readAndroidId(context)
        };
        for (String candidate : candidates) {
            String normalized = normalize(candidate);
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return "android-device-" + System.currentTimeMillis();
    }

    private static String readSystemSerial() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"sh", "-c", "getprop ro.serialno"});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                return reader.readLine();
            }
        } catch (Exception ignored) {
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String readBuildSerial() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return Build.getSerial();
            }
            return Build.SERIAL;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readAndroidId(Context context) {
        try {
            return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalize(String raw) {
        String value = raw != null ? raw.trim() : "";
        if (value.isEmpty()) {
            return "";
        }
        if ("unknown".equalsIgnoreCase(value) || "null".equalsIgnoreCase(value)) {
            return "";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
