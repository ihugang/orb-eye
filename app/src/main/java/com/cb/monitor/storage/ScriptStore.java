package com.cb.monitor.storage;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class ScriptStore {
    public static final class ScriptEntry {
        public final String fileName;
        public final long lastModified;
        public final long sizeBytes;

        ScriptEntry(String fileName, long lastModified, long sizeBytes) {
            this.fileName = fileName != null ? fileName : "";
            this.lastModified = lastModified;
            this.sizeBytes = sizeBytes;
        }
    }

    private static final String DIR_NAME = "js";
    private final File rootDir;

    public ScriptStore(Context context) {
        rootDir = new File(context.getFilesDir(), DIR_NAME);
        if (!rootDir.exists()) {
            rootDir.mkdirs();
        }
    }

    public List<ScriptEntry> listScripts() {
        File[] files = rootDir.listFiles((dir, name) -> name != null && name.toLowerCase().endsWith(".js"));
        List<ScriptEntry> entries = new ArrayList<>();
        if (files == null) {
            return entries;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File file : files) {
            entries.add(new ScriptEntry(file.getName(), file.lastModified(), file.length()));
        }
        return entries;
    }

    public String importScript(ContentResolver resolver, Uri uri, String preferredName) throws Exception {
        if (resolver == null || uri == null) {
            throw new IllegalArgumentException("resolver and uri are required");
        }
        String fileName = sanitizeFileName(preferredName);
        File target = new File(rootDir, fileName);
        try (InputStream in = resolver.openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target, false)) {
            if (in == null) {
                throw new IllegalStateException("unable to open selected file");
            }
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        }
        return target.getName();
    }

    public String saveScript(String preferredName, String scriptBody) throws Exception {
        String fileName = sanitizeFileName(preferredName);
        File target = new File(rootDir, fileName);
        byte[] content = (scriptBody != null ? scriptBody : "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(target, false)) {
            out.write(content);
        }
        return target.getName();
    }

    public String readScript(String fileName) throws Exception {
        File file = new File(rootDir, sanitizeFileName(fileName));
        if (!file.exists()) {
            throw new IllegalStateException("script file does not exist");
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.max(1L, file.length())];
            int total = 0;
            int read;
            while ((read = in.read(buffer, total, buffer.length - total)) > 0) {
                total += read;
                if (total == buffer.length) {
                    break;
                }
            }
            return new String(buffer, 0, total, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    public boolean deleteScript(String fileName) {
        File file = new File(rootDir, sanitizeFileName(fileName));
        return file.exists() && file.delete();
    }

    private String sanitizeFileName(String raw) {
        String normalized = raw != null ? raw.trim() : "";
        if (normalized.isEmpty()) {
            normalized = "script-" + System.currentTimeMillis() + ".js";
        }
        normalized = normalized.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!normalized.toLowerCase().endsWith(".js")) {
            normalized = normalized + ".js";
        }
        return normalized;
    }
}
