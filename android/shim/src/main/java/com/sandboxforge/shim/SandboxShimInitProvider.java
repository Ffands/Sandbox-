package com.sandboxforge.shim;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * High-priority ContentProvider injected into cloned APKs (initOrder=MAX_VALUE).
 * Runs before Application.onCreate() on every Android version (API 26–37):
 * 1. Installs SandboxShield (original signature + Google Play installer spoofing)
 * 2. Writes boot marker into clone files/
 * 3. Sends live SHIM_BOOT telemetry back to SandboxForge
 */
public class SandboxShimInitProvider extends ContentProvider {
    private static final String TAG = "SandboxShim";

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) return false;

        try {
            SandboxConfig cfg = SandboxConfig.loadFromContext(context);
            String shieldStatus = SandboxShield.install(context, cfg);

            String actualPkg = context.getPackageName();
            String dataDir = context.getApplicationInfo().dataDir;
            boolean is64Bit = Process.is64Bit();
            int pid = Process.myPid();

            writeMarker(context, cfg, actualPkg, pid, is64Bit, shieldStatus);

            Intent ping = new Intent(SandboxConfig.ACTION_SHIM_EVENT);
            ping.setPackage(cfg.hostPackage);
            ping.putExtra("event", "SHIM_BOOT");
            ping.putExtra("containerId", cfg.containerId);
            ping.putExtra("originalPackage", cfg.originalPackage);
            ping.putExtra("clonePackage", actualPkg);
            ping.putExtra("pid", pid);
            ping.putExtra("is64Bit", is64Bit);
            ping.putExtra("androidApi", Build.VERSION.SDK_INT);
            ping.putExtra("dataDir", dataDir != null ? dataDir : "");
            ping.putExtra("shimVersion", cfg.shimVersion);
            ping.putExtra("shieldStatus", shieldStatus);
            ping.putExtra("bridgeReady", true);
            context.sendBroadcast(ping);

            Log.i(TAG, "Shim booted inside " + actualPkg
                    + " (pid=" + pid + ", 64bit=" + is64Bit + ", shield=" + shieldStatus + ")");
        } catch (Throwable t) {
            Log.e(TAG, "SandboxShimInitProvider boot error", t);
        }
        return true;
    }

    private void writeMarker(
            Context context,
            SandboxConfig cfg,
            String actualPkg,
            int pid,
            boolean is64Bit,
            String shieldStatus
    ) {
        try {
            File marker = new File(context.getFilesDir(), "sandboxforge_shim_boot.txt");
            String text = "host=" + cfg.hostPackage
                    + "\ncontainer=" + cfg.containerId
                    + "\noriginalPackage=" + cfg.originalPackage
                    + "\nclonePackage=" + actualPkg
                    + "\nshimVersion=" + cfg.shimVersion
                    + "\npid=" + pid
                    + "\nis64Bit=" + is64Bit
                    + "\napi=" + Build.VERSION.SDK_INT
                    + "\nshield=" + shieldStatus
                    + "\nbootTimeMs=" + System.currentTimeMillis() + "\n";
            try (FileOutputStream fos = new FileOutputStream(marker, false)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
