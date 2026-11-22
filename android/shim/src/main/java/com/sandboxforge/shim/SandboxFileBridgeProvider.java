package com.sandboxforge.shim;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Triple-locked live filesystem bridge inside cloned APKs.
 * Exposes the clone's private dataDir (/data/user/0/<clonePkg>/) and externalFilesDir
 * EXCLUSIVELY to the SandboxForge host app.
 *
 * Security checks on EVERY call() and openFile():
 * 1. callingPackage == cfg.hostPackage
 * 2. Binder.getCallingUid() == pm.getPackageUid(cfg.hostPackage)
 *    AND host signing certificate SHA-256 == cfg.hostCertSha256
 * 3. Constant-time match of per-clone 256-bit bridgeToken
 * 4. Strict canonicalPath jail inside internal dataDir or externalFilesDir
 */
public class SandboxFileBridgeProvider extends ContentProvider {
    private static final String TAG = "SandboxFileBridge";
    private static final int MAX_PREVIEW_BYTES = 128 * 1024;

    private volatile SandboxConfig cachedConfig;

    @Override
    public boolean onCreate() {
        return true;
    }

    private SandboxConfig getConfig(Context ctx) {
        SandboxConfig cfg = cachedConfig;
        if (cfg == null) {
            cfg = SandboxConfig.loadFromContext(ctx);
            cachedConfig = cfg;
        }
        return cfg;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        if (ctx == null) {
            throw new IllegalStateException("Context unavailable");
        }
        SandboxConfig cfg = getConfig(ctx);
        String token = extras != null ? extras.getString("token", "") : "";
        enforceAuthorizedCaller(ctx, cfg, token);

        String rootKey = extras != null ? extras.getString("root", "internal") : "internal";
        String relPath = extras != null ? extras.getString("path", "") : "";

        Bundle out = new Bundle();
        try {
            if ("ping".equals(method)) {
                out.putBoolean("ok", true);
                out.putString("shimVersion", cfg.shimVersion);
                out.putString("clonePackage", ctx.getPackageName());
                out.putString("internalRoot", resolveRootDir(ctx, "internal").getAbsolutePath());
                File ext = ctx.getExternalFilesDir(null);
                out.putString("externalRoot", ext != null ? ext.getAbsolutePath() : "");
                out.putInt("pid", Process.myPid());
                out.putString("shieldStatus", SandboxShield.getStatusSummary());
                return out;
            }

            if ("list".equals(method)) {
                File dir = resolveJailedFile(ctx, rootKey, relPath);
                if (!dir.exists() || !dir.isDirectory()) {
                    throw new IOException("Каталог не существует: " + relPath);
                }
                File[] children = dir.listFiles();
                if (children == null) children = new File[0];
                Arrays.sort(children, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        if (a.isDirectory() != b.isDirectory()) {
                            return a.isDirectory() ? -1 : 1;
                        }
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });

                File rootDir = resolveRootDir(ctx, rootKey).getCanonicalFile();
                JSONArray items = new JSONArray();
                for (File f : children) {
                    JSONObject obj = new JSONObject();
                    obj.put("name", f.getName());
                    obj.put("relativePath", toRelativePath(rootDir, f.getCanonicalFile()));
                    obj.put("isDirectory", f.isDirectory());
                    long size = f.isDirectory() ? countChildren(f) : f.length();
                    obj.put("sizeBytes", size);
                    obj.put("lastModified", f.lastModified());
                    obj.put("isReadOnly", !f.canWrite());
                    items.put(obj);
                }
                out.putBoolean("ok", true);
                out.putString("currentPath", toRelativePath(rootDir, dir));
                out.putString("itemsJson", items.toString());
                return out;
            }

            if ("mkdir".equals(method)) {
                String folderName = extras != null ? extras.getString("name", "") : "";
                if (folderName.isEmpty() || folderName.contains("/") || folderName.contains("\\")
                        || ".".equals(folderName) || "..".equals(folderName)) {
                    throw new IllegalArgumentException("Недопустимое имя папки");
                }
                File parent = resolveJailedFile(ctx, rootKey, relPath);
                File target = resolveJailedChild(ctx, rootKey, parent, folderName);
                if (target.exists()) {
                    throw new IOException("Папка уже существует");
                }
                if (!target.mkdirs()) {
                    throw new IOException("Не удалось создать папку");
                }
                out.putBoolean("ok", true);
                return out;
            }

            if ("delete".equals(method)) {
                File rootDir = resolveRootDir(ctx, rootKey).getCanonicalFile();
                File target = resolveJailedFile(ctx, rootKey, relPath);
                if (target.equals(rootDir)) {
                    throw new SecurityException("Нельзя удалить корневой каталог клона");
                }
                if (!target.exists()) {
                    throw new FileNotFoundException("Объект не найден");
                }
                boolean deleted = deleteRecursively(target);
                out.putBoolean("ok", deleted);
                if (!deleted) {
                    out.putString("error", "Не удалось полностью удалить объект");
                }
                return out;
            }

            if ("clearCache".equals(method)) {
                long[] stats = new long[2]; // [deletedCount, freedBytes]
                clearDirContents(ctx.getCacheDir(), stats);
                clearDirContents(ctx.getCodeCacheDir(), stats);
                File extCache = ctx.getExternalCacheDir();
                if (extCache != null) {
                    clearDirContents(extCache, stats);
                }
                out.putBoolean("ok", true);
                out.putLong("deletedCount", stats[0]);
                out.putLong("freedBytes", stats[1]);
                return out;
            }

            if ("readPreview".equals(method)) {
                File file = resolveJailedFile(ctx, rootKey, relPath);
                if (!file.exists() || !file.isFile()) {
                    throw new FileNotFoundException("Файл не найден");
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int total = 0;
                boolean truncated = false;
                try (FileInputStream fis = new FileInputStream(file)) {
                    int read;
                    while ((read = fis.read(buf)) != -1) {
                        if (total + read > MAX_PREVIEW_BYTES) {
                            bos.write(buf, 0, MAX_PREVIEW_BYTES - total);
                            truncated = true;
                            break;
                        }
                        bos.write(buf, 0, read);
                        total += read;
                    }
                }
                byte[] data = bos.toByteArray();
                String content = isLikelyBinary(data)
                        ? formatHexPreview(data, file.length())
                        : new String(data, StandardCharsets.UTF_8);
                out.putBoolean("ok", true);
                out.putString("content", content);
                out.putBoolean("truncated", truncated);
                out.putLong("fileSize", file.length());
                return out;
            }

            throw new IllegalArgumentException("Неизвестный метод моста: " + method);
        } catch (Throwable t) {
            Log.w(TAG, "Bridge call failed [" + method + "]: " + t.getMessage());
            out.putBoolean("ok", false);
            out.putString("error", t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            return out;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null) {
            throw new FileNotFoundException("Context unavailable");
        }
        SandboxConfig cfg = getConfig(ctx);
        String token = uri.getQueryParameter("token");
        enforceAuthorizedCaller(ctx, cfg, token != null ? token : "");

        String rootKey = uri.getQueryParameter("root");
        if (rootKey == null || rootKey.isEmpty()) rootKey = "internal";
        String relPath = uri.getQueryParameter("path");
        if (relPath == null) relPath = "";

        try {
            File target = resolveJailedFile(ctx, rootKey, relPath);
            if ("r".equals(mode)) {
                if (!target.exists() || !target.isFile()) {
                    throw new FileNotFoundException("Файл не найден: " + relPath);
                }
                return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
            } else if ("w".equals(mode) || "wt".equals(mode)) {
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                return ParcelFileDescriptor.open(
                        target,
                        ParcelFileDescriptor.MODE_WRITE_ONLY
                                | ParcelFileDescriptor.MODE_CREATE
                                | ParcelFileDescriptor.MODE_TRUNCATE
                );
            } else {
                throw new SecurityException("Неподдерживаемый режим открытия: " + mode);
            }
        } catch (FileNotFoundException fnf) {
            throw fnf;
        } catch (SecurityException se) {
            throw se;
        } catch (Throwable t) {
            FileNotFoundException fnf = new FileNotFoundException(t.getMessage());
            fnf.initCause(t);
            throw fnf;
        }
    }

    private void enforceAuthorizedCaller(Context ctx, SandboxConfig cfg, String providedToken) {
        String callerPkg = getCallingPackage();
        if (callerPkg == null || !callerPkg.equals(cfg.hostPackage)) {
            Log.w(TAG, "Denied bridge access from callingPackage=" + callerPkg);
            throw new SecurityException("Доступ разрешён только SandboxForge");
        }

        int callingUid = Binder.getCallingUid();
        PackageManager pm = ctx.getPackageManager();
        try {
            int expectedUid = pm.getPackageUid(cfg.hostPackage, 0);
            if (callingUid != expectedUid) {
                Log.w(TAG, "Denied bridge access: UID mismatch (" + callingUid + " != " + expectedUid + ")");
                throw new SecurityException("Несовпадение UID вызывающего приложения");
            }
            if (cfg.hostCertSha256 != null && !cfg.hostCertSha256.isEmpty()) {
                if (!matchesPackageCertSha256(pm, cfg.hostPackage, cfg.hostCertSha256)) {
                    Log.w(TAG, "Denied bridge access: host certificate SHA-256 mismatch");
                    throw new SecurityException("Несовпадение сертификата SandboxForge");
                }
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Throwable t) {
            Log.w(TAG, "Denied bridge access: unable to verify host UID/cert: " + t.getMessage());
            throw new SecurityException("Не удалось проверить подлинность SandboxForge: " + t.getMessage());
        }

        if (cfg.bridgeToken == null || cfg.bridgeToken.isEmpty() || providedToken == null) {
            throw new SecurityException("Отсутствует токен безопасности моста");
        }
        byte[] expectedBytes = cfg.bridgeToken.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = providedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
            Log.w(TAG, "Denied bridge access: invalid bridge token");
            throw new SecurityException("Неверный токен безопасности моста");
        }
    }

    @SuppressWarnings("deprecation")
    private boolean matchesPackageCertSha256(PackageManager pm, String pkg, String expectedHex) throws Exception {
        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo si = info.signingInfo;
            if (si != null) {
                sigs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
            }
        } else {
            PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            sigs = info.signatures;
        }
        if (sigs == null || sigs.length == 0) return false;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Signature sig : sigs) {
            byte[] digest = md.digest(sig.toByteArray());
            if (expectedHex.equalsIgnoreCase(toHex(digest))) {
                return true;
            }
        }
        return false;
    }

    private File resolveRootDir(Context ctx, String rootKey) throws IOException {
        if ("external".equals(rootKey)) {
            File ext = ctx.getExternalFilesDir(null);
            if (ext == null) {
                throw new IOException("Внешний каталог Android/data недоступен");
            }
            if (!ext.exists()) ext.mkdirs();
            return ext;
        }
        String dataPath = ctx.getApplicationInfo().dataDir;
        File dataDir = dataPath != null ? new File(dataPath) : ctx.getFilesDir().getParentFile();
        if (dataDir == null) {
            throw new IOException("Внутренний каталог dataDir недоступен");
        }
        return dataDir;
    }

    private File resolveJailedFile(Context ctx, String rootKey, String relPath) throws IOException {
        if (relPath != null && relPath.indexOf('\0') >= 0) {
            throw new SecurityException("Некорректный путь");
        }
        File rootCanonical = resolveRootDir(ctx, rootKey).getCanonicalFile();
        String cleaned = relPath == null ? "" : relPath.trim();
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        File candidate = cleaned.isEmpty() ? rootCanonical : new File(rootCanonical, cleaned);
        File targetCanonical = candidate.getCanonicalFile();
        String rootPrefix = rootCanonical.getPath();
        String targetPath = targetCanonical.getPath();
        if (!targetPath.equals(rootPrefix) && !targetPath.startsWith(rootPrefix + File.separator)) {
            throw new SecurityException("Выход за пределы песочницы клона запрещён");
        }
        return targetCanonical;
    }

    private File resolveJailedChild(Context ctx, String rootKey, File parent, String childName) throws IOException {
        File rootCanonical = resolveRootDir(ctx, rootKey).getCanonicalFile();
        File targetCanonical = new File(parent, childName).getCanonicalFile();
        String rootPrefix = rootCanonical.getPath();
        if (!targetCanonical.getPath().startsWith(rootPrefix + File.separator)) {
            throw new SecurityException("Выход за пределы песочницы клона запрещён");
        }
        return targetCanonical;
    }

    private String toRelativePath(File rootCanonical, File targetCanonical) {
        String rootPath = rootCanonical.getPath();
        String targetPath = targetCanonical.getPath();
        if (targetPath.equals(rootPath)) return "/";
        if (targetPath.startsWith(rootPath + File.separator)) {
            return "/" + targetPath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
        }
        return "/";
    }

    private long countChildren(File dir) {
        File[] list = dir.listFiles();
        return list != null ? list.length : 0L;
    }

    private boolean deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        f.setWritable(true);
        return f.delete();
    }

    private void clearDirContents(File dir, long[] stats) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            accumulateAndDelete(child, stats);
        }
    }

    private void accumulateAndDelete(File f, long[] stats) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    accumulateAndDelete(child, stats);
                }
            }
        } else {
            stats[1] += f.length();
        }
        if (f.delete()) {
            stats[0] += 1;
        }
    }

    private boolean isLikelyBinary(byte[] data) {
        int checkLen = Math.min(data.length, 4096);
        int nonPrintable = 0;
        for (int i = 0; i < checkLen; i++) {
            int b = data[i] & 0xFF;
            if (b == 0) return true;
            if (b < 0x09 || (b > 0x0D && b < 0x20)) {
                nonPrintable++;
            }
        }
        return checkLen > 0 && (nonPrintable * 100 / checkLen) > 15;
    }

    private String formatHexPreview(byte[] data, long totalSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Бинарный файл, размер: ").append(totalSize).append(" байт]\n");
        sb.append("Используй кнопку «Экспорт» для сохранения копии.\n\n");
        int limit = Math.min(data.length, 512);
        for (int i = 0; i < limit; i += 16) {
            sb.append(String.format("%04X: ", i));
            for (int j = 0; j < 16; j++) {
                if (i + j < limit) {
                    sb.append(String.format("%02X ", data[i + j] & 0xFF));
                } else {
                    sb.append("   ");
                }
            }
            sb.append(" ");
            for (int j = 0; j < 16 && i + j < limit; j++) {
                int b = data[i + j] & 0xFF;
                sb.append((b >= 32 && b < 127) ? (char) b : '.');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
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
