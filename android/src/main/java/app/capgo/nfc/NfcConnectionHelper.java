package app.capgo.nfc;

import android.nfc.tech.TagTechnology;
import android.util.Log;

/**
 * Shared NFC {@link TagTechnology} cleanup used when stale tags can make {@link TagTechnology#close()}
 * throw {@link SecurityException}.
 */
final class NfcConnectionHelper {

    private static final String TAG = "CapacitorNfcPlugin";

    private NfcConnectionHelper() {}

    static void closeQuietly(TagTechnology tech) {
        if (tech == null) {
            return;
        }
        try {
            if (tech.isConnected()) {
                tech.close();
            }
        } catch (Exception ex) {
            try {
                Log.w(TAG, "Failed to close NFC technology connection", ex);
            } catch (RuntimeException logError) {
                // android.util.Log is unavailable in JVM unit tests without a device runtime.
            }
        }
    }
}
