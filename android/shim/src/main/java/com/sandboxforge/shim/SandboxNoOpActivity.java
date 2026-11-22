package com.sandboxforge.shim;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/**
 * Harmless replacement Activity instantiated when a blocked PairIP license redirect
 * Activity (com.pairip.licensecheck.LicenseActivity) is launched dynamically.
 */
public class SandboxNoOpActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i("SandboxShim", "Shield intercepted blocked license Activity -> finishing cleanly");
        finish();
    }
}
