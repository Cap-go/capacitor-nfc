package app.capgo.nfc;

import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.Tag;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import com.getcapacitor.PluginCall;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class CapacitorNfcPluginConnectionTest {

    @Rule
    public Timeout globalTimeout = Timeout.seconds(10);

    @Test(timeout = 5000)
    public void readNdefFromMifareUltralight_closesOnConnectIOException() throws Exception {
        CapacitorNfcPlugin plugin = new CapacitorNfcPlugin();
        MifareUltralight mifare = mock(MifareUltralight.class);
        when(mifare.isConnected()).thenReturn(true);
        doThrow(new IOException("connect failed")).when(mifare).connect();

        Method method = CapacitorNfcPlugin.class.getDeclaredMethod("readNdefFromMifareUltralight", MifareUltralight.class);
        method.setAccessible(true);
        assertNull(method.invoke(plugin, mifare));

        verify(mifare, atLeastOnce()).close();
    }

    @Test(timeout = 5000)
    public void readNdefFromMifareUltralight_closesOnSecurityException() throws Exception {
        CapacitorNfcPlugin plugin = new CapacitorNfcPlugin();
        MifareUltralight mifare = mock(MifareUltralight.class);
        when(mifare.isConnected()).thenReturn(true);
        doThrow(new SecurityException("Tag out of date")).when(mifare).connect();

        Method method = CapacitorNfcPlugin.class.getDeclaredMethod("readNdefFromMifareUltralight", MifareUltralight.class);
        method.setAccessible(true);
        assertNull(method.invoke(plugin, mifare));

        verify(mifare, atLeastOnce()).close();
    }

    @Test(timeout = 5000)
    public void performWrite_closesNdefOnReadOnlyReject() throws Exception {
        CapacitorNfcPlugin plugin = new CapacitorNfcPlugin();
        setSyncExecutor(plugin);
        Tag tag = mock(Tag.class);
        Ndef ndef = mock(Ndef.class);
        NdefMessage message = new NdefMessage(new NdefRecord[] { NdefRecord.createTextRecord("en", "hi") });

        when(ndef.isConnected()).thenReturn(true);
        when(ndef.isWritable()).thenReturn(false);

        PluginCall call = mock(PluginCall.class);

        try (MockedStatic<Ndef> ndefStatic = Mockito.mockStatic(Ndef.class)) {
            ndefStatic.when(() -> Ndef.get(any(Tag.class))).thenReturn(ndef);

            Method method = CapacitorNfcPlugin.class.getDeclaredMethod(
                "performWrite",
                com.getcapacitor.PluginCall.class,
                Tag.class,
                NdefMessage.class,
                boolean.class
            );
            method.setAccessible(true);
            method.invoke(plugin, call, tag, message, false);
        }

        verify(ndef, atLeastOnce()).close();
    }

    @Test(timeout = 5000)
    public void performWrite_closesFormatableWhenFormatThrows() throws Exception {
        CapacitorNfcPlugin plugin = new CapacitorNfcPlugin();
        setSyncExecutor(plugin);
        Tag tag = mock(Tag.class);
        NdefFormatable formatable = mock(NdefFormatable.class);
        NdefMessage message = new NdefMessage(new NdefRecord[] { NdefRecord.createTextRecord("en", "hi") });

        when(formatable.isConnected()).thenReturn(true);
        doThrow(new IOException("format failed")).when(formatable).format(any(NdefMessage.class));

        PluginCall call = mock(PluginCall.class);

        try (
            MockedStatic<Ndef> ndefStatic = Mockito.mockStatic(Ndef.class);
            MockedStatic<NdefFormatable> formatableStatic = Mockito.mockStatic(NdefFormatable.class)
        ) {
            ndefStatic.when(() -> Ndef.get(tag)).thenReturn(null);
            formatableStatic.when(() -> NdefFormatable.get(any(Tag.class))).thenReturn(formatable);

            Method method = CapacitorNfcPlugin.class.getDeclaredMethod(
                "performWrite",
                com.getcapacitor.PluginCall.class,
                Tag.class,
                NdefMessage.class,
                boolean.class
            );
            method.setAccessible(true);
            method.invoke(plugin, call, tag, message, true);
        }

        verify(formatable, atLeastOnce()).close();
    }

    @Test(timeout = 5000)
    public void makeReadOnly_closesAfterSuccessfulLockEvenWhenCloseThrows() throws Exception {
        CapacitorNfcPlugin plugin = new CapacitorNfcPlugin();
        setSyncExecutor(plugin);
        Tag tag = mock(Tag.class);
        Ndef ndef = mock(Ndef.class);

        when(ndef.isConnected()).thenReturn(true);
        when(ndef.makeReadOnly()).thenReturn(true);
        doThrow(new SecurityException("Tag out of date")).when(ndef).close();

        setLastTag(plugin, tag);
        PluginCall call = mock(PluginCall.class);

        try (MockedStatic<Ndef> ndefStatic = Mockito.mockStatic(Ndef.class)) {
            ndefStatic.when(() -> Ndef.get(any(Tag.class))).thenReturn(ndef);
            plugin.makeReadOnly(call);
        }

        verify(ndef, atLeastOnce()).close();
    }

    private static void setLastTag(CapacitorNfcPlugin plugin, Tag tag) throws Exception {
        java.lang.reflect.Field field = CapacitorNfcPlugin.class.getDeclaredField("lastTag");
        field.setAccessible(true);
        java.util.concurrent.atomic.AtomicReference<Tag> ref = (java.util.concurrent.atomic.AtomicReference<Tag>) field.get(plugin);
        ref.set(tag);
    }

    private static void setSyncExecutor(CapacitorNfcPlugin plugin) throws Exception {
        java.lang.reflect.Field field = CapacitorNfcPlugin.class.getDeclaredField("executor");
        field.setAccessible(true);
        ExecutorService direct = new AbstractExecutorService() {
            @Override
            public void shutdown() {}

            @Override
            public List<Runnable> shutdownNow() {
                return Collections.emptyList();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return true;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }

            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
        field.set(plugin, direct);
    }
}
