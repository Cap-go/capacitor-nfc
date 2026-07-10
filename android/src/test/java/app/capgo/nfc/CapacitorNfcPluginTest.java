package app.capgo.nfc;

import static org.junit.Assert.assertEquals;

import android.nfc.NfcAdapter;
import org.junit.Test;

public class CapacitorNfcPluginTest {

    @Test
    public void defaultReaderFlagsKeepNdefDiscoveryEnabled() {
        assertEquals(0, CapacitorNfcPlugin.DEFAULT_READER_FLAGS & NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK);
    }
}
