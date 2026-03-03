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
import android.nfc.tech.NfcV;
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
    private static final String pluginVersion = "7.0.6";

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

        // Clear any stale tag reference from previous scans
        lastTag.set(null);
        lastMessage.set(null);

        readerModeFlags = call.getInt("androidReaderModeFlags", DEFAULT_READER_FLAGS);
        readerModeRequested = true;
        enableReaderMode(readerModeFlags);
        call.resolve();
    }

    @PluginMethod
    public void stopScanning(PluginCall call) {
        readerModeRequested = false;
        disableReaderMode(true);
        // Clear the tag reference to prevent stale writes
        lastTag.set(null);
        lastMessage.set(null);
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

    @PluginMethod
    public void isSupported(PluginCall call) {
        JSObject result = new JSObject();
        result.put("supported", adapter != null);
        call.resolve(result);
    }

    private void performWrite(PluginCall call, Tag tag, NdefMessage message, boolean allowFormat) {
        executor.execute(() -> {
            String[] techList = tag.getTechList();

            // Check if this is a MIFARE Ultralight tag
            boolean hasMifareUltralight = Arrays.asList(techList).contains("android.nfc.tech.MifareUltralight");
            boolean hasNfcV = Arrays.asList(techList).contains("android.nfc.tech.NfcV");

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
                } else if (hasMifareUltralight) {
                    // Ndef.get() returns null when FLAG_READER_SKIP_NDEF_CHECK is used
                    // For MIFARE Ultralight tags, we can write NDEF directly using raw page writes
                    MifareUltralight mifare = MifareUltralight.get(tag);
                    if (mifare != null) {
                        boolean success = writeNdefToMifareUltralight(mifare, message);
                        if (success) {
                            call.resolve();
                        } else {
                            call.reject("Failed to write NDEF message to MIFARE Ultralight tag.");
                        }
                    } else {
                        call.reject("Could not access MIFARE Ultralight tag for writing.");
                    }
                } else if (allowFormat) {
                    NdefFormatable formatable = NdefFormatable.get(tag);
                    if (formatable != null) {
                        formatable.connect();
                        formatable.format(message);
                        formatable.close();
                        call.resolve();
                    } else {
                        // Check if this is an ISO 15693 (NfcV) tag which typically doesn't support NDEF
                        if (hasNfcV) {
                            call.reject(
                                "This ISO 15693 tag does not support NDEF. These raw tags can only be read, not written with NDEF messages."
                            );
                        } else {
                            call.reject("Tag does not support NDEF formatting. Tech types: " + Arrays.toString(techList));
                        }
                    }
                } else {
                    call.reject("Tag does not support NDEF.");
                }
            } catch (IOException | FormatException e) {
                call.reject("Failed to write NDEF message.", e);
            }
        });
    }

    /**
     * Writes NDEF message to MIFARE Ultralight tag using raw page writes.
     *
     * This is needed when FLAG_READER_SKIP_NDEF_CHECK is used, which causes Ndef.get() to return null.
     * MIFARE Ultralight tags use NFC Forum Type 2 Tag format:
     * - Pages 0-3: UID and lock bits (read-only)
     * - Page 4+: User data area where NDEF is stored in TLV format
     *
     * TLV format:
     * - 0x03: NDEF Message TLV
     * - Length: 1 byte if < 0xFF, or 0xFF + 2 bytes if >= 0xFF
     * - NDEF message bytes
     * - 0xFE: Terminator TLV
     */
    private boolean writeNdefToMifareUltralight(MifareUltralight mifare, NdefMessage message) {
        try {
            mifare.connect();

            byte[] ndefBytes = message.toByteArray();

            // Build TLV structure
            byte[] tlvData;
            if (ndefBytes.length < 0xFF) {
                // Short format: Type (1) + Length (1) + NDEF + Terminator (1)
                tlvData = new byte[1 + 1 + ndefBytes.length + 1];
                tlvData[0] = 0x03; // NDEF Message TLV
                tlvData[1] = (byte) ndefBytes.length;
                System.arraycopy(ndefBytes, 0, tlvData, 2, ndefBytes.length);
                tlvData[tlvData.length - 1] = (byte) 0xFE; // Terminator TLV
            } else {
                // Long format: Type (1) + 0xFF (1) + Length (2) + NDEF + Terminator (1)
                tlvData = new byte[1 + 1 + 2 + ndefBytes.length + 1];
                tlvData[0] = 0x03; // NDEF Message TLV
                tlvData[1] = (byte) 0xFF; // Long length indicator
                tlvData[2] = (byte) ((ndefBytes.length >> 8) & 0xFF);
                tlvData[3] = (byte) (ndefBytes.length & 0xFF);
                System.arraycopy(ndefBytes, 0, tlvData, 4, ndefBytes.length);
                tlvData[tlvData.length - 1] = (byte) 0xFE; // Terminator TLV
            }

            // MIFARE Ultralight page size is 4 bytes, user data starts at page 4
            int startPage = 4;
            int pageSize = 4;
            int pagesNeeded = (tlvData.length + pageSize - 1) / pageSize;

            // Check tag capacity
            int tagType = mifare.getType();
            int maxUserPages = (tagType == MifareUltralight.TYPE_ULTRALIGHT_C) ? 36 : 12;

            if (pagesNeeded > maxUserPages) {
                mifare.close();
                return false;
            }

            // Pad tlvData to page boundary
            int paddedLength = pagesNeeded * pageSize;
            byte[] paddedData = new byte[paddedLength];
            System.arraycopy(tlvData, 0, paddedData, 0, tlvData.length);

            // Write pages
            for (int i = 0; i < pagesNeeded; i++) {
                int page = startPage + i;
                byte[] pageData = new byte[pageSize];
                System.arraycopy(paddedData, i * pageSize, pageData, 0, pageSize);
                mifare.writePage(page, pageData);
            }

            mifare.close();
            return true;
        } catch (IOException e) {
            Log.w(TAG, "MIFARE Ultralight write failed", e);
            try {
                mifare.close();
            } catch (IOException closeEx) {
                // Ignore
            }
            return false;
        }
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
        boolean hasNfcV = Arrays.asList(techList).contains("android.nfc.tech.NfcV");
        boolean hasNdef = Arrays.asList(techList).contains("android.nfc.tech.Ndef");

        if (hasMifareUltralight) {
            // Try MIFARE Ultralight first - read immediately before tag becomes stale
            MifareUltralight mifare = MifareUltralight.get(tag);
            if (mifare != null) {
                message = readNdefFromMifareUltralight(mifare);
            }
        }

        // For NfcV (ISO 15693 / Type 5) tags:
        // Always attempt raw block read since FLAG_READER_SKIP_NDEF_CHECK means
        // hasNdef is always false, but the tag may still contain NDEF data
        if (message == null && hasNfcV) {
            NfcV nfcV = NfcV.get(tag);
            if (nfcV != null) {
                message = readNdefFromNfcV(nfcV, tag);
            }
        }

        // If no message from MIFARE or NfcV, try standard NDEF
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

            // Read more pages for TLV scanning
            byte[] allData = new byte[64];
            System.arraycopy(firstPages, 0, allData, 0, firstPages.length);
            int bytesRead = firstPages.length;
            int currentPage = 8;

            try {
                byte[] morePages = mifare.readPages(8);
                if (morePages != null && morePages.length > 0) {
                    System.arraycopy(morePages, 0, allData, bytesRead, morePages.length);
                    bytesRead += morePages.length;
                    currentPage = 12;
                }
            } catch (IOException e) {
                // Tag might not support reading beyond page 7
            }

            try {
                byte[] evenMorePages = mifare.readPages(12);
                if (evenMorePages != null && evenMorePages.length > 0) {
                    System.arraycopy(evenMorePages, 0, allData, bytesRead, Math.min(evenMorePages.length, allData.length - bytesRead));
                    bytesRead += Math.min(evenMorePages.length, allData.length - bytesRead);
                    currentPage = 16;
                }
            } catch (IOException e) {
                // Tag might not support reading beyond page 11
            }

            // Scan through the data looking for NDEF TLV (0x03)
            // TLV format: Type (1 byte) + Length (1 or 3 bytes) + Value
            // Common TLV types:
            // - 0x00: NULL TLV (skip)
            // - 0x01: Lock Control TLV
            // - 0x02: Memory Control TLV
            // - 0x03: NDEF Message TLV
            // - 0xFE: Terminator TLV
            int offset = 0;
            int ndefOffset = -1;
            int ndefLength = 0;

            while (offset < bytesRead - 1) {
                int tlvType = allData[offset] & 0xFF;

                if (tlvType == 0x00) {
                    // NULL TLV - skip single byte
                    offset++;
                } else if (tlvType == 0xFE) {
                    // Terminator TLV - end of data
                    mifare.close();
                    return null;
                } else if (tlvType == 0x03) {
                    // NDEF Message TLV found
                    if ((allData[offset + 1] & 0xFF) == 0xFF) {
                        // 3-byte length format
                        if (offset + 3 >= bytesRead) {
                            mifare.close();
                            return null;
                        }
                        ndefLength = ((allData[offset + 2] & 0xFF) << 8) | (allData[offset + 3] & 0xFF);
                        ndefOffset = offset + 4;
                    } else {
                        // 1-byte length format
                        ndefLength = allData[offset + 1] & 0xFF;
                        ndefOffset = offset + 2;
                    }
                    break;
                } else if (tlvType == 0x01 || tlvType == 0x02) {
                    // Lock Control TLV or Memory Control TLV - skip
                    int skipLen = allData[offset + 1] & 0xFF;
                    offset += 2 + skipLen;
                } else {
                    // Unknown TLV type - try to skip it
                    if (offset + 1 < bytesRead) {
                        int skipLen = allData[offset + 1] & 0xFF;
                        if (skipLen == 0xFF && offset + 3 < bytesRead) {
                            skipLen = ((allData[offset + 2] & 0xFF) << 8) | (allData[offset + 3] & 0xFF);
                            offset += 4 + skipLen;
                        } else {
                            offset += 2 + skipLen;
                        }
                    } else {
                        break;
                    }
                }
            }

            if (ndefOffset < 0 || ndefLength == 0 || ndefLength > 1024) {
                mifare.close();
                return null;
            }

            // Read more data if needed
            int totalBytesNeeded = ndefOffset + ndefLength;
            while (bytesRead < totalBytesNeeded && currentPage < 260) {
                try {
                    byte[] pages = mifare.readPages(currentPage);
                    if (pages == null || pages.length == 0) {
                        break;
                    }
                    // Expand allData if needed
                    if (bytesRead + pages.length > allData.length) {
                        byte[] newData = new byte[bytesRead + pages.length + 64];
                        System.arraycopy(allData, 0, newData, 0, bytesRead);
                        allData = newData;
                    }
                    System.arraycopy(pages, 0, allData, bytesRead, pages.length);
                    bytesRead += pages.length;
                    currentPage += 4;
                } catch (IOException e) {
                    break;
                }
            }

            mifare.close();

            if (bytesRead < totalBytesNeeded) {
                Log.w(
                    TAG,
                    String.format(
                        "Incomplete NDEF read: read %d bytes, needed %d bytes (stopped at page %d, variant: %s)",
                        bytesRead,
                        totalBytesNeeded,
                        currentPage,
                        variantName
                    )
                );
                return null;
            }

            // Extract NDEF data
            byte[] ndefData = new byte[ndefLength];
            System.arraycopy(allData, ndefOffset, ndefData, 0, ndefLength);

            try {
                return new NdefMessage(ndefData);
            } catch (FormatException e) {
                Log.w(TAG, "Failed to parse NDEF message from MIFARE Ultralight", e);
                return null;
            }
        } catch (SecurityException | IOException e) {
            try {
                mifare.close();
            } catch (Exception closeEx) {
                // Ignore
            }
            return null;
        }
    }

    /**
     * Reads NDEF message from NfcV (ISO 15693 / NFC Type 5) tag.
     *
     * Based on NFC Forum Type 5 Tag Operation specification:
     * - Uses READ_SINGLE_BLOCK command (0x20) to read blocks
     * - NDEF data is stored in TLV format
     * - Block size is typically 4 bytes
     *
     * References:
     * - Android NfcV API: https://developer.android.com/reference/android/nfc/tech/NfcV
     * - ISO/IEC 15693-3 standard
     * - NFC Forum Type 5 Tag Operation specification
     */
    private NdefMessage readNdefFromNfcV(NfcV nfcV, Tag tag) {
        try {
            nfcV.connect();

            byte dsfId = nfcV.getDsfId();
            byte responseFlags = nfcV.getResponseFlags();
            int maxTransceiveLength = nfcV.getMaxTransceiveLength();

            // Get tag UID for addressed commands
            byte[] uid = tag.getId();

            // Try to get system information first (optional, command 0x2B)
            int blockSize = 4; // Default block size for most ISO 15693 tags
            int numBlocks = 256; // Default max, will be limited by actual read attempts

            try {
                byte[] getSystemInfoCmd = new byte[10];
                getSystemInfoCmd[0] = 0x22; // Flags: addressed, high data rate
                getSystemInfoCmd[1] = 0x2B; // GET_SYSTEM_INFO command
                // Copy UID in reverse order (LSB first for ISO 15693)
                for (int i = 0; i < 8; i++) {
                    getSystemInfoCmd[2 + i] = uid[7 - i];
                }

                byte[] systemInfo = nfcV.transceive(getSystemInfoCmd);
                if (systemInfo != null && systemInfo.length >= 2 && (systemInfo[0] & 0x01) == 0) {
                    // Parse system info response
                    int infoFlags = systemInfo[1] & 0xFF;
                    int offset = 10; // Skip flags + UID

                    // Check if DSFID is present (bit 0 of info flags)
                    if ((infoFlags & 0x01) != 0) {
                        offset++;
                    }
                    // Check if AFI is present (bit 1 of info flags)
                    if ((infoFlags & 0x02) != 0) {
                        offset++;
                    }
                    // Check if memory size is present (bit 2 of info flags)
                    if ((infoFlags & 0x04) != 0 && systemInfo.length > offset + 1) {
                        numBlocks = (systemInfo[offset] & 0xFF) + 1;
                        blockSize = (systemInfo[offset + 1] & 0x1F) + 1;
                    }
                }
            } catch (IOException e) {
                // System info not supported, use defaults
            }

            // Read blocks to find and parse NDEF TLV
            // Type 5 tags store NDEF in CC (Capability Container) + NDEF TLV format
            // CC is in block 0, NDEF starts after

            // Read first blocks to get CC and find NDEF
            byte[] allData = new byte[numBlocks * blockSize];
            int bytesRead = 0;
            int maxBlocksToRead = Math.min(numBlocks, 64); // Limit initial read

            for (int block = 0; block < maxBlocksToRead; block++) {
                try {
                    // READ_SINGLE_BLOCK command (addressed mode)
                    byte[] readCmd = new byte[11];
                    readCmd[0] = 0x22; // Flags: addressed, high data rate
                    readCmd[1] = 0x20; // READ_SINGLE_BLOCK command
                    // Copy UID in reverse order
                    for (int i = 0; i < 8; i++) {
                        readCmd[2 + i] = uid[7 - i];
                    }
                    readCmd[10] = (byte) block;

                    byte[] response = nfcV.transceive(readCmd);

                    if (response != null && response.length > 1 && (response[0] & 0x01) == 0) {
                        // Skip response flags byte, copy block data
                        int dataLen = Math.min(response.length - 1, blockSize);
                        System.arraycopy(response, 1, allData, bytesRead, dataLen);
                        bytesRead += dataLen;
                    } else {
                        // Error response or no more blocks
                        break;
                    }
                } catch (IOException e) {
                    // Block read failed, stop reading
                    break;
                }
            }

            nfcV.close();

            if (bytesRead < 4) {
                Log.w(TAG, "NfcV: Not enough data read from tag");
                return null;
            }

            // Parse Capability Container (first 4 bytes typically)
            // CC[0] = Magic number (0xE1 for NFC Forum Type 5)
            // CC[1] = Version + access conditions
            // CC[2] = Memory size / 8
            // CC[3] = Feature flags

            int ccMagic = allData[0] & 0xFF;
            if (ccMagic != 0xE1 && ccMagic != 0xE2) {
                // Not an NDEF formatted tag, but still emit the raw tag info
                return null;
            }

            // Find NDEF TLV (Type = 0x03) starting after CC
            int tlvOffset = (ccMagic == 0xE2) ? 8 : 4; // Extended CC (0xE2) is 8 bytes
            int ndefLength = 0;
            int ndefDataOffset = 0;

            while (tlvOffset < bytesRead - 1) {
                int tlvType = allData[tlvOffset] & 0xFF;

                if (tlvType == 0x00) {
                    // NULL TLV, skip
                    tlvOffset++;
                    continue;
                } else if (tlvType == 0xFE) {
                    // Terminator TLV
                    break;
                } else if (tlvType == 0x03) {
                    // NDEF Message TLV found
                    if ((allData[tlvOffset + 1] & 0xFF) == 0xFF) {
                        // 3-byte length format
                        if (tlvOffset + 3 >= bytesRead) break;
                        ndefLength = ((allData[tlvOffset + 2] & 0xFF) << 8) | (allData[tlvOffset + 3] & 0xFF);
                        ndefDataOffset = tlvOffset + 4;
                    } else {
                        // 1-byte length format
                        ndefLength = allData[tlvOffset + 1] & 0xFF;
                        ndefDataOffset = tlvOffset + 2;
                    }
                    break;
                } else {
                    // Other TLV, skip it
                    int skipLen;
                    if ((allData[tlvOffset + 1] & 0xFF) == 0xFF) {
                        if (tlvOffset + 3 >= bytesRead) break;
                        skipLen = ((allData[tlvOffset + 2] & 0xFF) << 8) | (allData[tlvOffset + 3] & 0xFF);
                        tlvOffset += 4 + skipLen;
                    } else {
                        skipLen = allData[tlvOffset + 1] & 0xFF;
                        tlvOffset += 2 + skipLen;
                    }
                }
            }

            if (ndefLength == 0 || ndefDataOffset + ndefLength > bytesRead) {
                // NfcV: No valid NDEF TLV found or data incomplete
                return null;
            }

            // Extract NDEF message data
            byte[] ndefData = new byte[ndefLength];
            System.arraycopy(allData, ndefDataOffset, ndefData, 0, ndefLength);

            try {
                NdefMessage message = new NdefMessage(ndefData);
                return message;
            } catch (FormatException e) {
                Log.w(TAG, "NfcV: Failed to parse NDEF message", e);
                return null;
            }
        } catch (SecurityException e) {
            try {
                nfcV.close();
            } catch (Exception closeEx) {
                // Ignore
            }
            return null;
        } catch (IOException e) {
            Log.w(TAG, "NfcV: IO error reading tag", e);
            try {
                nfcV.close();
            } catch (IOException closeEx) {
                // Ignore
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
