package com.sandboxforge.shim;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility Shield inside SandboxShim (v0.2.2):
 * 1. Spoofs original developer APK signature(s) in PackageManager queries for the cloned package.
 * 2. Spoofs installer package / InstallSourceInfo to Google Play (com.android.vending).
 * 3. Fails gracefully on Android Hidden API restrictions and reports exact diagnostic status.
 */
public final class SandboxShield {
    private static final String TAG = "SandboxShield";

    private static volatile boolean installed = false;
    private static volatile String statusSummary = "not_initialized";

    private SandboxShield() {
    }

    public static String getStatusSummary() {
        return statusSummary;
    }

    public static synchronized String install(Context context, SandboxConfig cfg) {
        if (installed) {
            return statusSummary;
        }
        installed = true;

        if (!cfg.shieldEnabled) {
            statusSummary = "disabled";
            return statusSummary;
        }

        Signature[] origSigs = decodeSignatures(cfg.origCertsBase64);
        String clonePkg = context.getPackageName();
        String origPkg = cfg.originalPackage != null ? cfg.originalPackage : "";
        String spoofInstaller = (cfg.spoofInstaller != null && !cfg.spoofInstaller.isEmpty())
                ? cfg.spoofInstaller
                : "com.android.vending";

        String pmStatus = hookPackageManager(context, clonePkg, origPkg, spoofInstaller, origSigs);
        statusSummary = "pm=" + pmStatus
                + ",certs=" + origSigs.length
                + ",pairipOff=" + cfg.pairIpDisabledCount
                + ",installer=" + spoofInstaller;
        Log.i(TAG, "Shield initialized: " + statusSummary);
        return statusSummary;
    }

    private static Signature[] decodeSignatures(String[] certsBase64) {
        if (certsBase64 == null || certsBase64.length == 0) {
            return new Signature[0];
        }
        List<Signature> list = new ArrayList<>();
        for (String b64 : certsBase64) {
            if (b64 == null || b64.isEmpty()) continue;
            try {
                byte[] der = Base64.decode(b64, Base64.DEFAULT);
                if (der != null && der.length > 0) {
                    list.add(new Signature(der));
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to decode orig cert", t);
            }
        }
        return list.toArray(new Signature[0]);
    }

    private static String hookPackageManager(
            Context context,
            String clonePkg,
            String origPkg,
            String spoofInstaller,
            Signature[] origSigs
    ) {
        boolean hookedGlobal = false;
        boolean hookedLocal = false;
        String lastErr = null;

        try {
            Class<?> iPmClass = Class.forName("android.content.pm.IPackageManager");
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");

            // 1. Hook ActivityThread.sPackageManager
            try {
                Method currentAtMethod = activityThreadClass.getDeclaredMethod("currentActivityThread");
                currentAtMethod.setAccessible(true);
                Object currentAt = currentAtMethod.invoke(null);

                Field sPmField = activityThreadClass.getDeclaredField("sPackageManager");
                sPmField.setAccessible(true);
                Object origPm = sPmField.get(currentAt);
                if (origPm != null && !Proxy.isProxyClass(origPm.getClass())) {
                    Object proxy = createPmProxy(iPmClass, origPm, clonePkg, origPkg, spoofInstaller, origSigs);
                    sPmField.set(currentAt, proxy);
                    hookedGlobal = true;
                }
            } catch (Throwable t) {
                lastErr = t.getClass().getSimpleName();
                Log.w(TAG, "ActivityThread.sPackageManager hook skipped: " + t);
            }

            // 2. Hook ApplicationPackageManager.mPM on the current Context
            try {
                PackageManager pm = context.getPackageManager();
                if (pm != null) {
                    Field mPmField = findFieldRecursive(pm.getClass(), "mPM");
                    if (mPmField != null) {
                        mPmField.setAccessible(true);
                        Object localOrig = mPmField.get(pm);
                        if (localOrig != null && !Proxy.isProxyClass(localOrig.getClass())) {
                            Object proxy = createPmProxy(iPmClass, localOrig, clonePkg, origPkg, spoofInstaller, origSigs);
                            mPmField.set(pm, proxy);
                            hookedLocal = true;
                        }
                    }
                }
            } catch (Throwable t) {
                lastErr = t.getClass().getSimpleName();
                Log.w(TAG, "ApplicationPackageManager.mPM hook skipped: " + t);
            }
        } catch (Throwable t) {
            lastErr = t.getClass().getSimpleName();
            Log.w(TAG, "IPackageManager reflection unavailable: " + t);
        }

        if (hookedGlobal && hookedLocal) return "OK(global+local)";
        if (hookedGlobal) return "OK(global)";
        if (hookedLocal) return "OK(local)";
        return "BLOCKED(" + (lastErr != null ? lastErr : "unknown") + ")";
    }

    private static Object createPmProxy(
            Class<?> iPmClass,
            final Object delegate,
            final String clonePkg,
            final String origPkg,
            final String spoofInstaller,
            final Signature[] origSigs
    ) {
        return Proxy.newProxyInstance(
                iPmClass.getClassLoader(),
                new Class<?>[]{iPmClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String name = method.getName();

                        // Spoof legacy getInstallerPackageName(pkg)
                        if ("getInstallerPackageName".equals(name) && args != null && args.length >= 1) {
                            if (isTargetPackage(args[0], clonePkg, origPkg)) {
                                return spoofInstaller;
                            }
                        }

                        // Spoof Android 11+ getInstallSourceInfo(pkg, ...)
                        if ("getInstallSourceInfo".equals(name) && args != null && args.length >= 1) {
                            Object real = invokeUnwrapped(method, delegate, args);
                            if (isTargetPackage(args[0], clonePkg, origPkg) && real != null) {
                                Object spoofed = tryBuildSpoofedInstallSourceInfo(real, spoofInstaller);
                                if (spoofed != null) return spoofed;
                            }
                            return real;
                        }

                        // Spoof PackageInfo signatures / SigningInfo
                        if (("getPackageInfo".equals(name) || "getPackageInfoVersioned".equals(name))
                                && args != null && args.length >= 1) {
                            boolean match = isTargetPackage(args[0], clonePkg, origPkg);
                            Object result = invokeUnwrapped(method, delegate, args);
                            if (match && result instanceof PackageInfo && origSigs.length > 0) {
                                patchPackageInfoSignatures((PackageInfo) result, origSigs);
                            }
                            return result;
                        }

                        return invokeUnwrapped(method, delegate, args);
                    }
                }
        );
    }

    private static Object invokeUnwrapped(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            throw ite.getTargetException();
        }
    }

    private static boolean isTargetPackage(Object arg0, String clonePkg, String origPkg) {
        if (arg0 == null) return false;
        String pkg = null;
        if (arg0 instanceof String) {
            pkg = (String) arg0;
        } else {
            // VersionedPackage on getPackageInfoVersioned
            try {
                Method getPkg = arg0.getClass().getMethod("getPackageName");
                Object v = getPkg.invoke(arg0);
                if (v instanceof String) pkg = (String) v;
            } catch (Throwable ignored) {
            }
        }
        if (pkg == null) return false;
        return pkg.equals(clonePkg) || (!origPkg.isEmpty() && pkg.equals(origPkg));
    }

    @SuppressWarnings("deprecation")
    private static void patchPackageInfoSignatures(PackageInfo info, Signature[] origSigs) {
        try {
            if (info.signatures != null) {
                info.signatures = origSigs.clone();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
                Field detailsField = findFieldRecursive(info.signingInfo.getClass(), "mSigningDetails");
                if (detailsField != null) {
                    detailsField.setAccessible(true);
                    Object details = detailsField.get(info.signingInfo);
                    if (details != null) {
                        Field sigsField = findFieldRecursive(details.getClass(), "mSignatures");
                        if (sigsField == null) {
                            sigsField = findFieldRecursive(details.getClass(), "signatures");
                        }
                        if (sigsField != null) {
                            sigsField.setAccessible(true);
                            sigsField.set(details, origSigs.clone());
                        }
                        Field pastField = findFieldRecursive(details.getClass(), "mPastSigningCertificates");
                        if (pastField == null) {
                            pastField = findFieldRecursive(details.getClass(), "pastSigningCertificates");
                        }
                        if (pastField != null) {
                            pastField.setAccessible(true);
                            pastField.set(details, origSigs.clone());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "SigningInfo field update skipped: " + t.getClass().getSimpleName());
        }
    }

    private static Object tryBuildSpoofedInstallSourceInfo(Object real, String spoofInstaller) {
        try {
            // Try mutating string fields directly first (works across Android 11–17 constructor changes)
            String[] fieldNames = new String[]{
                    "mInitiatingPackageName",
                    "mInstallingPackageName",
                    "mOriginatingPackageName"
            };
            boolean mutated = false;
            for (String fn : fieldNames) {
                Field f = findFieldRecursive(real.getClass(), fn);
                if (f != null && f.getType() == String.class) {
                    f.setAccessible(true);
                    f.set(real, spoofInstaller);
                    mutated = true;
                }
            }
            if (mutated) {
                return real;
            }

            // Fallback: try 4-String constructor if available
            for (Constructor<?> ctor : real.getClass().getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length >= 3 && params[0] == String.class && params[2] == String.class) {
                    Object[] vals = new Object[params.length];
                    for (int i = 0; i < params.length; i++) {
                        if (params[i] == String.class) {
                            vals[i] = spoofInstaller;
                        } else if (params[i] == int.class) {
                            vals[i] = 2; // PACKAGE_SOURCE_STORE
                        } else if (params[i] == boolean.class) {
                            vals[i] = false;
                        } else {
                            vals[i] = null;
                        }
                    }
                    ctor.setAccessible(true);
                    return ctor.newInstance(vals);
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "InstallSourceInfo spoof skipped: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private static Field findFieldRecursive(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
