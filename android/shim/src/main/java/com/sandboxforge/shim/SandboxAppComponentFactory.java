package com.sandboxforge.shim;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

/**
 * First entry point injected into cloned APKs (Android 9–17 / API 28+).
 * 1. Cleanly delegates component instantiation to the guest app's original AppComponentFactory.
 * 2. Intercepts PairIP licensecheck components (LicenseActivity / LicenseContentProvider)
 *    before they can redirect the user to Google Play.
 */
@TargetApi(Build.VERSION_CODES.P)
public class SandboxAppComponentFactory extends AppComponentFactory {
    private static final String TAG = "SandboxShim";

    private volatile AppComponentFactory originalDelegate;
    private volatile boolean delegateResolved = false;
    private volatile boolean shieldEnabled = true;

    private AppComponentFactory resolveDelegate(ClassLoader cl) {
        if (delegateResolved) {
            return originalDelegate;
        }
        synchronized (this) {
            if (delegateResolved) {
                return originalDelegate;
            }
            try {
                SandboxConfig cfg = SandboxConfig.loadFromClassLoader(cl);
                shieldEnabled = cfg.shieldEnabled;
                String origName = cfg.originalAppComponentFactory;
                if (origName != null
                        && !origName.isEmpty()
                        && !origName.equals(SandboxAppComponentFactory.class.getName())) {
                    Class<?> clazz = cl.loadClass(origName);
                    Object instance = clazz.getDeclaredConstructor().newInstance();
                    if (instance instanceof AppComponentFactory) {
                        originalDelegate = (AppComponentFactory) instance;
                        Log.i(TAG, "Delegated AppComponentFactory -> " + origName);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Could not instantiate original AppComponentFactory, using default", t);
            } finally {
                delegateResolved = true;
            }
            return originalDelegate;
        }
    }

    private boolean isBlockedPairIpClass(String className) {
        if (!shieldEnabled || className == null) return false;
        return className.startsWith("com.pairip.licensecheck.");
    }

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo aInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AppComponentFactory delegate = resolveDelegate(cl);
            if (delegate != null) {
                return delegate.instantiateClassLoader(cl, aInfo);
            }
            return super.instantiateClassLoader(cl, aInfo);
        }
        return cl;
    }

    @Override
    public Application instantiateApplication(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory delegate = resolveDelegate(cl);
        if (delegate != null) {
            return delegate.instantiateApplication(cl, className);
        }
        return super.instantiateApplication(cl, className);
    }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory delegate = resolveDelegate(cl);
        if (isBlockedPairIpClass(className)) {
            Log.i(TAG, "Shield intercepted PairIP Activity: " + className);
            return new SandboxNoOpActivity();
        }
        if (delegate != null) {
            return delegate.instantiateActivity(cl, className, intent);
        }
        return super.instantiateActivity(cl, className, intent);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory delegate = resolveDelegate(cl);
        if (delegate != null) {
            return delegate.instantiateReceiver(cl, className, intent);
        }
        return super.instantiateReceiver(cl, className, intent);
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory delegate = resolveDelegate(cl);
        if (delegate != null) {
            return delegate.instantiateService(cl, className, intent);
        }
        return super.instantiateService(cl, className, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory delegate = resolveDelegate(cl);
        if (isBlockedPairIpClass(className)) {
            Log.i(TAG, "Shield intercepted PairIP Provider: " + className);
            return new SandboxNoOpProvider();
        }
        if (delegate != null) {
            return delegate.instantiateProvider(cl, className);
        }
        return super.instantiateProvider(cl, className);
    }
}
