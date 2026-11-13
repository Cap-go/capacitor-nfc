package app.capgo.nfc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.nfc.Tag;
import android.nfc.tech.TagTechnology;
import java.io.IOException;
import org.junit.Test;

public class NfcConnectionHelperTest {

    @Test
    public void closeQuietly_noOpsWhenDisconnected() {
        RecordingTagTechnology tech = new RecordingTagTechnology(false);
        NfcConnectionHelper.closeQuietly(tech);
        assertFalse(tech.closeInvoked);
    }

    @Test
    public void closeQuietly_closesWhenConnected() throws IOException {
        RecordingTagTechnology tech = new RecordingTagTechnology(true);
        NfcConnectionHelper.closeQuietly(tech);
        assertTrue(tech.closeInvoked);
    }

    @Test
    public void closeQuietly_swallowsSecurityException() {
        RecordingTagTechnology tech = new RecordingTagTechnology(true);
        tech.failWithSecurityException = true;
        NfcConnectionHelper.closeQuietly(tech);
        assertTrue(tech.closeInvoked);
    }

    @Test
    public void closeQuietly_swallowsIOException() {
        RecordingTagTechnology tech = new RecordingTagTechnology(true);
        tech.failWithIOException = true;
        NfcConnectionHelper.closeQuietly(tech);
        assertTrue(tech.closeInvoked);
    }

    @Test
    public void closeQuietly_acceptsNull() {
        NfcConnectionHelper.closeQuietly(null);
    }

    private static final class RecordingTagTechnology implements TagTechnology {

        private final boolean connected;
        boolean closeInvoked;
        boolean failWithSecurityException;
        boolean failWithIOException;

        RecordingTagTechnology(boolean connected) {
            this.connected = connected;
        }

        @Override
        public Tag getTag() {
            return null;
        }

        @Override
        public void connect() {}

        @Override
        public void close() throws IOException {
            closeInvoked = true;
            if (failWithSecurityException) {
                throw new SecurityException("Tag ... is out of date");
            }
            if (failWithIOException) {
                throw new IOException("close failed");
            }
        }

        @Override
        public boolean isConnected() {
            return connected;
        }
    }
}
