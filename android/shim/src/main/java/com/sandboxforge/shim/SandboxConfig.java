package com.sandboxforge.shim;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Lightweight configuration embedded inside cloned APKs at assets/sandbox_config.json.
 * Zero external dependencies so it never collides with guest app libraries.
 */
public final class SandboxConfig {
    private static final String TAG = "SandboxShim";
    public static final String ASSET_NAME = "sandbox_config.json";
    public static final String ACTION_SHIM_EVENT = "com.sandboxforge.action.SHIM_EVENT";

    public final String hostPackage;
    public final String hostCertSha256;
    public final String bridgeToken;
    public final String containerId;
    public final String originalPackage;
    public final String clonePackage;
    public final String originalAppComponentFactory;
    public final String shimVersion;
    public final String spoofInstaller;
    public final boolean shieldEnabled;
    public final String[] origCertsBase64;
    public final int pairIpDisabledCount;

    private SandboxConfig(
            String hostPackage,
            String hostCertSha256,
            String bridgeToken,
            String containerId,
            String originalPackage,
            String clonePackage,
            String originalAppComponentFactory,
            String shimVersion,
            String spoofInstaller,
            boolean shieldEnabled,
            String[] origCertsBase64,
            int pairIpDisabledCount
    ) {
        this.hostPackage = hostPackage;
        this.hostCertSha256 = hostCertSha256;
        this.bridgeToken = bridgeToken;
        this.containerId = containerId;
        this.originalPackage = originalPackage;
        this.clonePackage = clonePackage;
        this.originalAppComponentFactory = originalAppComponentFactory;
        this.shimVersion = shimVersion;
        this.spoofInstaller = spoofInstaller;
        this.shieldEnabled = shieldEnabled;
        this.origCertsBase64 = origCertsBase64;
        this.pairIpDisabledCount = pairIpDisabledCount;
    }

    public static SandboxConfig loadFromClassLoader(ClassLoader classLoader) {
        if (classLoader == null) return fallback();
        try (InputStream is = classLoader.getResourceAsStream("assets/" + ASSET_NAME)) {
            if (is == null) return fallback();
            return parse(readAllText(is));
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load sandbox_config.json from ClassLoader", t);
            return fallback();
        }
    }

    public static SandboxConfig loadFromContext(Context context) {
        if (context == null) return fallback();
        try (InputStream is = context.getAssets().open(ASSET_NAME)) {
            return parse(readAllText(is));
        } catch (Throwable t) {
            return loadFromClassLoader(context.getClassLoader());
        }
    }

    private static SandboxConfig parse(String raw) {
        try {
            JSONObject obj = new JSONObject(raw);
            JSONArray certsArr = obj.optJSONArray("origCertsBase64");
            String[] certs = new String[0];
            if (certsArr != null && certsArr.length() > 0) {
                certs = new String[certsArr.length()];
                for (int i = 0; i < certsArr.length(); i++) {
                    certs[i] = certsArr.optString(i, "");
                }
            }
            return new SandboxConfig(
                    obj.optString("hostPackage", "com.sandboxforge.app"),
                    obj.optString("hostCertSha256", ""),
                    obj.optString("bridgeToken", ""),
                    obj.optString("containerId", "default"),
                    obj.optString("originalPackage", ""),
                    obj.optString("clonePackage", ""),
                    obj.optString("originalAppComponentFactory", ""),
                    obj.optString("shimVersion", "0.2.2"),
                    obj.optString("spoofInstaller", "com.android.vending"),
                    obj.optBoolean("shieldEnabled", true),
                    certs,
                    obj.optInt("pairIpDisabledCount", 0)
            );
        } catch (Throwable t) {
            Log.w(TAG, "Invalid sandbox_config.json", t);
            return fallback();
        }
    }

    private static SandboxConfig fallback() {
        return new SandboxConfig(
                "com.sandboxforge.app",
                "",
                "",
                "default",
                "",
                "",
                "",
                "0.2.2",
                "com.android.vending",
                true,
                new String[0],
                0
        );
    }

    private static String readAllText(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int read;
        while ((read = is.read(buf)) != -1) {
            bos.write(buf, 0, read);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
