package app.capgo.nfc;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.nfc.tech.NfcA;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONException;

@CapacitorPlugin(name = "CapacitorNfc")
public class CapacitorNfcPlugin extends Plugin {

    private static final String TAG = "CapacitorNfcPlugin";
    private static final String pluginVersion = "7.0.5";
    private static final int DEFAULT_READER_FLAGS =
        NfcAdapter.FLAG_READER_NFC_A |
        NfcAdapter.FLAG_READER_NFC_B |
        NfcAdapter.FLAG_READER_NFC_F |
        NfcAdapter.FLAG_READER_NFC_V |
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK |
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS;

    private NfcAdapter adapter;
    private final AtomicReference<Tag> lastTag = new AtomicReference<>(null);
    private final AtomicReference<NdefMessage> lastMessage = new AtomicReference<>(null);
    private boolean readerModeRequested = false;
    private boolean readerModeActive = false;
    private int readerModeFlags = DEFAULT_READER_FLAGS;
    private NdefMessage sharedMessage = null;
    private NfcStateReceiver stateReceiver;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final NfcAdapter.ReaderCallback readerCallback = this::onTagDiscovered;

    @Override
    public void load() {
        adapter = NfcAdapter.getDefaultAdapter(getContext());
        registerStateReceiver();
        emitStateChange(adapter != null && adapter.isEnabled() ? NfcAdapter.STATE_ON : NfcAdapter.STATE_OFF);
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        unregisterStateReceiver();
        executor.shutdownNow();
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        if (readerModeActive) {
            disableReaderMode(false);
        }
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        if (readerModeRequested && !readerModeActive) {
            enableReaderMode(readerModeFlags);
        }
    }

    @PluginMethod
    public void startScanning(PluginCall call) {
        if (!ensureAdapterAvailable(call)) {
            return;
        }

        readerModeFlags = call.getInt("androidReaderModeFlags", DEFAULT_READER_FLAGS);
        readerModeRequested = true;
        enableReaderMode(readerModeFlags);
        call.resolve();
    }

    @PluginMethod
    public void stopScanning(PluginCall call) {
        readerModeRequested = false;
        disableReaderMode(true);
        call.resolve();
    }

    @PluginMethod
    public void write(PluginCall call) {
        JSONArray records = call.getArray("records");
        boolean allowFormat = call.getBoolean("allowFormat", true);

        if (records == null) {
            call.reject("records is required");
            return;
        }

        Tag tag = lastTag.get();
        if (tag == null) {
            call.reject("No NFC tag available. Call startScanning and tap a tag before attempting to write.");
            return;
        }

        try {
            NdefMessage message = NfcJsonConverter.jsonArrayToMessage(records);
            performWrite(call, tag, message, allowFormat);
        } catch (JSONException e) {
            call.reject("Invalid NDEF records payload", e);
        }
    }

    @PluginMethod
    public void erase(PluginCall call) {
        Tag tag = lastTag.get();
        if (tag == null) {
            call.reject("No NFC tag available. Call startScanning and tap a tag before attempting to erase.");
            return;
        }

        NdefRecord empty = new NdefRecord(NdefRecord.TNF_EMPTY, new byte[0], new byte[0], new byte[0]);
        NdefMessage message = new NdefMessage(new NdefRecord[] { empty });
        performWrite(call, tag, message, true);
    }

    @PluginMethod
    public void makeReadOnly(PluginCall call) {
        Tag tag = lastTag.get();
        if (tag == null) {
            call.reject("No NFC tag available. Scan a tag before attempting to lock it.");
            return;
        }

        executor.execute(() -> {
            Ndef ndef = Ndef.get(tag);
            if (ndef == null) {
                call.reject("Tag does not support NDEF.");
                return;
            }

            try {
                ndef.connect();
                boolean success = ndef.makeReadOnly();
                ndef.close();
                if (success) {
                    call.resolve();
                } else {
                    call.reject("Failed to make the tag read only.");
                }
            } catch (IOException e) {
                call.reject("Failed to make the tag read only.", e);
            }
        });
    }

    @PluginMethod
    public void share(PluginCall call) {
        if (!ensureAdapterAvailable(call)) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            call.reject("Peer-to-peer NFC sharing is not supported on Android 10 or later.");
            return;
        }

        JSArray records = call.getArray("records");
        if (records == null) {
            call.reject("records is required");
            return;
        }

        try {
            NdefMessage message = NfcJsonConverter.jsonArrayToMessage(records);
            Activity activity = getActivity();
            if (activity == null) {
                call.reject("Unable to access activity context.");
                return;
            }

            activity.runOnUiThread(() -> {
                try {
                    if (!isNdefPushEnabled(adapter)) {
                        call.reject("NDEF push is disabled on this device.");
                        return;
                    }
                    setNdefPushMessage(adapter, message, activity);
                    sharedMessage = message;
                    call.resolve();
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
                    Log.w(TAG, "NDEF push API unavailable on this device", ex);
                    call.reject("NDEF push is not available on this device.");
                }
            });
        } catch (JSONException e) {
            call.reject("Invalid NDEF records payload", e);
        }
    }

    @PluginMethod
    public void unshare(PluginCall call) {
        if (!ensureAdapterAvailable(call)) {
            return;
        }

        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Unable to access activity context.");
            return;
        }

        activity.runOnUiThread(() -> {
            try {
                setNdefPushMessage(adapter, null, activity);
                sharedMessage = null;
                call.resolve();
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
                Log.w(TAG, "NDEF push API unavailable on this device", ex);
                call.reject("Unable to clear shared message on this device.");
            }
        });
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject result = new JSObject();
        result.put("status", getNfcStatus());
        call.resolve(result);
    }

    @PluginMethod
    public void showSettings(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Unable to open settings without an activity context.");
            return;
        }

        Intent intent = new Intent(Settings.ACTION_NFC_SETTINGS);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            Intent fallback = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
            try {
                activity.startActivity(fallback);
            } catch (ActivityNotFoundException secondary) {
                call.reject("Unable to open NFC settings on this device.");
                return;
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void getPluginVersion(PluginCall call) {
        JSObject result = new JSObject();
        result.put("version", pluginVersion);
        call.resolve(result);
    }

    private void performWrite(PluginCall call, Tag tag, NdefMessage message, boolean allowFormat) {
        executor.execute(() -> {
            Ndef ndef = Ndef.get(tag);
            try {
                if (ndef != null) {
                    ndef.connect();
                    if (!ndef.isWritable()) {
                        call.reject("Tag is read only.");
                    } else if (ndef.getMaxSize() < message.toByteArray().length) {
                        call.reject("Tag capacity is insufficient for the provided message.");
                    } else {
                        ndef.writeNdefMessage(message);
                        call.resolve();
                    }
                    ndef.close();
                } else if (allowFormat) {
                    NdefFormatable formatable = NdefFormatable.get(tag);
                    if (formatable != null) {
                        formatable.connect();
                        formatable.format(message);
                        formatable.close();
                        call.resolve();
                    } else {
                        call.reject("Tag does not support NDEF formatting.");
                    }
                } else {
                    call.reject("Tag does not support NDEF.");
                }
            } catch (IOException | FormatException e) {
                call.reject("Failed to write NDEF message.", e);
            }
        });
    }

    private void enableReaderMode(int flags) {
        Activity activity = getActivity();
        if (activity == null || adapter == null) {
            return;
        }

        Bundle extras = new Bundle();
        extras.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 100);

        activity.runOnUiThread(() -> {
            try {
                adapter.enableReaderMode(activity, readerCallback, flags, extras);
                readerModeActive = true;
            } catch (IllegalStateException ex) {
                Log.w(TAG, "Failed to enable reader mode", ex);
            }
        });
    }

    private void disableReaderMode(boolean clearRequested) {
        Activity activity = getActivity();
        if (activity == null || adapter == null) {
            return;
        }

        activity.runOnUiThread(() -> {
            try {
                adapter.disableReaderMode(activity);
            } catch (IllegalStateException ex) {
                Log.w(TAG, "Failed to disable reader mode", ex);
            } finally {
                readerModeActive = false;
                if (clearRequested) {
                    readerModeRequested = false;
                }
            }
        });
    }

    private void onTagDiscovered(Tag tag) {
        if (tag == null) {
            return;
        }

        NdefMessage message = null;
        String[] techList = tag.getTechList();
        
        // Check tech list first - if MIFARE Ultralight is present, prioritize it
        // (tags can become stale very quickly, so we need to read immediately)
        boolean hasMifareUltralight = Arrays.asList(techList).contains("android.nfc.tech.MifareUltralight");
        
        if (hasMifareUltralight) {
            // Try MIFARE Ultralight first - read immediately before tag becomes stale
            MifareUltralight mifare = MifareUltralight.get(tag);
            if (mifare != null) {
                message = readNdefFromMifareUltralight(mifare);
            }
        }
        
        // If no message from MIFARE, try standard NDEF
        if (message == null) {
            Ndef ndef = Ndef.get(tag);
            if (ndef != null) {
                // First try to get cached message (fast path)
                try {
                    message = ndef.getCachedNdefMessage();
                } catch (Exception ex) {
                    // Ignore - will try to read directly
                }
                
                // If no cached message, read it synchronously while tag is in range
                if (message == null) {
                    try {
                        ndef.connect();
                        message = ndef.getNdefMessage();
                        ndef.close();
                    } catch (IOException | FormatException ex) {
                        try {
                            ndef.close();
                        } catch (IOException closeEx) {
                            // Ignore close errors
                        }
                    }
                }
            }
        }

        lastTag.set(tag);
        lastMessage.set(message);
        emitTagEvent(tag, message);
    }

    /**
     * Reads NDEF message from MIFARE Ultralight tag by reading raw pages.
     * 
     * Based on NFC Forum Type 2 Tag Operation specification:
     * - NDEF data is stored in TLV (Type-Length-Value) format starting at page 4
     * - TLV Type 0x03 indicates NDEF message
     * - Length encoding: single byte if < 0xFF, or 0xFF + 2-byte length if >= 0xFF
     * 
     * References:
     * - Android MifareUltralight API: https://developer.android.com/reference/android/nfc/tech/MifareUltralight
     * - NFC Forum Type 2 Tag Operation specification
     */
    private NdefMessage readNdefFromMifareUltralight(MifareUltralight mifare) {
        try {
            // Connect immediately - tag can become stale quickly
            mifare.connect();
            
            // Log tag variant for debugging
            int tagType = mifare.getType();
            String variantName;
            switch (tagType) {
                case MifareUltralight.TYPE_ULTRALIGHT:
                    variantName = "MIFARE Ultralight (standard, 64 bytes)";
                    break;
                case MifareUltralight.TYPE_ULTRALIGHT_C:
                    variantName = "MIFARE Ultralight C (up to 192 bytes)";
                    break;
                default:
                    variantName = "MIFARE Ultralight (type: " + tagType + ", unknown variant)";
                    break;
            }
            Log.d(TAG, "MIFARE Ultralight tag variant: " + variantName);
            
            // Read pages 4-7 first (readPages reads 4 pages = 16 bytes at a time)
            // This contains the TLV header
            byte[] firstPages = mifare.readPages(4);
            if (firstPages == null || firstPages.length < 4) {
                mifare.close();
                return null;
            }
            
            // Check for NDEF TLV (Type = 0x03 per NFC Forum spec)
            if (firstPages[0] != 0x03) {
                mifare.close();
                return null;
            }
            
            // Parse TLV length field
            int ndefLength;
            int tlvHeaderSize;
            if ((firstPages[1] & 0xFF) < 0xFF) {
                // Short format: single byte length
                ndefLength = firstPages[1] & 0xFF;
                tlvHeaderSize = 2; // Type (1 byte) + Length (1 byte)
            } else {
                // Extended format: 0xFF + 2-byte length
                if (firstPages.length < 4) {
                    mifare.close();
                    return null;
                }
                ndefLength = ((firstPages[2] & 0xFF) << 8) | (firstPages[3] & 0xFF);
                tlvHeaderSize = 4; // Type (1 byte) + 0xFF (1 byte) + Length (2 bytes)
            }
            
            if (ndefLength == 0 || ndefLength > 1024) { // Reasonable upper limit
                mifare.close();
                return null;
            }
            
            // Calculate total bytes needed (TLV header + NDEF data)
            int totalBytesNeeded = tlvHeaderSize + ndefLength;
            int totalPagesNeeded = (totalBytesNeeded + 3) / 4; // Round up to pages
            
            // Read all necessary pages sequentially
            // Standard MIFARE Ultralight: pages 4-15 (48 bytes)
            // MIFARE Ultralight EV1: up to 256 pages (1024 bytes)
            // MIFARE Ultralight C: up to 256 pages (1024 bytes)
            byte[] allData = new byte[totalPagesNeeded * 4];
            int bytesRead = 0;
            int currentPage = 4;
            
            // Copy first 16 bytes we already read
            int bytesToCopy = Math.min(firstPages.length, allData.length);
            System.arraycopy(firstPages, 0, allData, 0, bytesToCopy);
            bytesRead = bytesToCopy;
            currentPage += 4; // Move to page 8
            
            // Read remaining pages if needed
            // Support up to page 256 (MIFARE Ultralight EV1/C maximum)
            while (bytesRead < totalBytesNeeded && currentPage < 260) {
                try {
                    byte[] pages = mifare.readPages(currentPage);
                    if (pages == null || pages.length == 0) {
                        // No more pages available - tag might not support this many pages
                        break;
                    }
                    
                    int bytesNeeded = totalBytesNeeded - bytesRead;
                    int bytesToRead = Math.min(pages.length, bytesNeeded);
                    System.arraycopy(pages, 0, allData, bytesRead, bytesToRead);
                    bytesRead += bytesToRead;
                    currentPage += 4; // readPages reads 4 pages at a time
                } catch (IOException e) {
                    // Tag might not support reading beyond this page
                    // This is normal for tags with fewer pages than needed
                    break;
                }
            }
            
            mifare.close();
            
            // Check if we have enough data
            if (bytesRead < totalBytesNeeded) {
                // Incomplete read - tag might not have enough pages or was removed
                Log.w(TAG, String.format("Incomplete NDEF read: read %d bytes, needed %d bytes (stopped at page %d, variant: %s)", 
                    bytesRead, totalBytesNeeded, currentPage, variantName));
                return null;
            }
            
            // Extract NDEF data (skip TLV header)
            byte[] ndefData = new byte[ndefLength];
            System.arraycopy(allData, tlvHeaderSize, ndefData, 0, ndefLength);
            
            // Parse NDEF message
            try {
                return new NdefMessage(ndefData);
            } catch (FormatException e) {
                Log.w(TAG, "Failed to parse NDEF message from MIFARE Ultralight", e);
                return null;
            }
        } catch (SecurityException e) {
            // Tag became stale - this happens if tag is removed or too much time passed
            try {
                mifare.close();
            } catch (Exception closeEx) {
                // Ignore close errors
            }
            return null;
        } catch (IOException e) {
            try {
                mifare.close();
            } catch (IOException closeEx) {
                // Ignore close errors
            }
            return null;
        }
    }

    private void emitTagEvent(Tag tag, NdefMessage message) {
        JSObject tagJson = NfcJsonConverter.tagToJSObject(tag, message);
        String eventType = determineEventType(tag, message);
        JSObject event = new JSObject();
        event.put("type", eventType);
        event.put("tag", tagJson);

        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        activity.runOnUiThread(() -> emitEvents(eventType, event));
    }

    private void emitEvents(String eventType, JSObject payload) {
        notifyListeners("nfcEvent", payload, true);
        switch (eventType) {
            case "tag":
                notifyListeners("tagDiscovered", payload, true);
                break;
            case "ndef":
                notifyListeners("ndefDiscovered", payload, true);
                break;
            case "ndef-mime":
                notifyListeners("ndefMimeDiscovered", payload, true);
                break;
            case "ndef-formatable":
                notifyListeners("ndefFormatableDiscovered", payload, true);
                break;
            default:
                break;
        }
    }

    private String determineEventType(Tag tag, NdefMessage message) {
        if (tag == null) {
            return "tag";
        }

        if (message != null) {
            if (Arrays.stream(message.getRecords()).anyMatch((record) -> record.getTnf() == NdefRecord.TNF_MIME_MEDIA)) {
                return "ndef-mime";
            }
            return "ndef";
        }

        if (Arrays.asList(tag.getTechList()).contains(NdefFormatable.class.getName())) {
            return "ndef-formatable";
        }

        return "tag";
    }

    private boolean ensureAdapterAvailable(PluginCall call) {
        if (adapter == null) {
            call.reject("NFC hardware not available on this device.", "NO_NFC");
            return false;
        }
        if (!adapter.isEnabled()) {
            call.reject("NFC is currently disabled.", "NFC_DISABLED");
            return false;
        }
        return true;
    }

    private String getNfcStatus() {
        if (adapter == null) {
            return "NO_NFC";
        }
        if (!adapter.isEnabled()) {
            return "NFC_DISABLED";
        }
        return "NFC_OK";
    }

    private boolean isNdefPushEnabled(NfcAdapter adapter) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = NfcAdapter.class.getMethod("isNdefPushEnabled");
        Object result = method.invoke(adapter);
        return result instanceof Boolean && (Boolean) result;
    }

    private void setNdefPushMessage(NfcAdapter adapter, NdefMessage message, Activity activity)
        throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = NfcAdapter.class.getMethod("setNdefPushMessage", NdefMessage.class, Activity.class, Activity[].class);
        method.invoke(adapter, message, activity, new Activity[0]);
    }

    private void registerStateReceiver() {
        if (stateReceiver != null) {
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return;
        }

        Context context = getContext();
        if (context == null) {
            return;
        }

        stateReceiver = new NfcStateReceiver(this::emitStateChange);
        IntentFilter filter = new IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
        context.registerReceiver(stateReceiver, filter);
    }

    private void unregisterStateReceiver() {
        if (stateReceiver == null) {
            return;
        }
        Context context = getContext();
        if (context != null) {
            context.unregisterReceiver(stateReceiver);
        }
        stateReceiver = null;
    }

    private void emitStateChange(int state) {
        String status;
        boolean enabled;
        switch (state) {
            case NfcAdapter.STATE_ON:
                status = "NFC_OK";
                enabled = true;
                break;
            case NfcAdapter.STATE_OFF:
                status = adapter == null ? "NO_NFC" : "NFC_DISABLED";
                enabled = false;
                break;
            default:
                status = getNfcStatus();
                enabled = adapter != null && adapter.isEnabled();
                break;
        }

        JSObject payload = new JSObject();
        payload.put("status", status);
        payload.put("enabled", enabled);
        notifyListeners("nfcStateChange", payload, true);
    }

    private static class NfcStateReceiver extends android.content.BroadcastReceiver {

        private final StateCallback callback;

        NfcStateReceiver(StateCallback callback) {
            this.callback = callback;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !NfcAdapter.ACTION_ADAPTER_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            int state = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF);
            if (callback != null) {
                callback.onStateChanged(state);
            }
        }
    }

    private interface StateCallback {
        void onStateChanged(int state);
    }
}
