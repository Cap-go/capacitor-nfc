package app.capgo.nfc;

import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONException;

final class NfcJsonConverter {
  private static final String TAG = "CapacitorNfcJson";

  private NfcJsonConverter() {}

  static JSObject tagToJSObject(Tag tag, NdefMessage cachedMessage) {
    JSObject result = new JSObject();
    if (tag != null) {
      result.put("id", byteArrayToJSONArray(tag.getId()));
      result.put("techTypes", techTypesToArray(tag.getTechList()));
    }

    if (tag != null) {
      Ndef ndef = Ndef.get(tag);
      if (ndef != null) {
        result.put("type", translateType(ndef.getType()));
        result.put("maxSize", ndef.getMaxSize());
        result.put("isWritable", ndef.isWritable());
        try {
          result.put("canMakeReadOnly", ndef.canMakeReadOnly());
        } catch (NullPointerException e) {
          result.put("canMakeReadOnly", JSObject.NULL);
        }
      }
    }

    if (cachedMessage != null) {
      result.put("ndefMessage", messageToJSONArray(cachedMessage));
    }

    return result;
  }

  static JSONArray messageToJSONArray(NdefMessage message) {
    if (message == null) {
      return null;
    }

    JSArray array = new JSArray();
    for (NdefRecord record : message.getRecords()) {
      array.put(recordToJSObject(record));
    }
    return array;
  }

  static JSObject recordToJSObject(NdefRecord record) {
    JSObject obj = new JSObject();
    obj.put("tnf", record.getTnf());
    obj.put("type", byteArrayToJSONArray(record.getType()));
    obj.put("id", byteArrayToJSONArray(record.getId()));
    obj.put("payload", byteArrayToJSONArray(record.getPayload()));
    return obj;
  }

  static JSONArray byteArrayToJSONArray(byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    JSArray array = new JSArray();
    for (byte aByte : bytes) {
      array.put(aByte & 0xFF);
    }
    return array;
  }

  private static JSArray techTypesToArray(String[] techTypes) {
    JSArray array = new JSArray();
    if (techTypes != null) {
      Arrays.stream(techTypes).forEach(array::put);
    }
    return array;
  }

  static String translateType(String type) {
    if (type == null) {
      return null;
    }
    switch (type) {
      case Ndef.NFC_FORUM_TYPE_1:
        return "NFC Forum Type 1";
      case Ndef.NFC_FORUM_TYPE_2:
        return "NFC Forum Type 2";
      case Ndef.NFC_FORUM_TYPE_3:
        return "NFC Forum Type 3";
      case Ndef.NFC_FORUM_TYPE_4:
        return "NFC Forum Type 4";
      default:
        return type;
    }
  }

  static NdefMessage jsonArrayToMessage(JSONArray records) throws JSONException {
    if (records == null || records.length() == 0) {
      throw new JSONException("records must be a non-empty array");
    }
    NdefRecord[] ndefRecords = new NdefRecord[records.length()];
    for (int i = 0; i < records.length(); i++) {
      JSObject record = JSObject.fromJSONObject(records.getJSONObject(i));
      Integer tnfValue = record.getInteger("tnf");
      if (tnfValue == null) {
        throw new JSONException("Each record must include a tnf field.");
      }
      short tnf = (short) tnfValue.intValue();
      byte[] type = jsonArrayToBytes(record.optJSONArray("type"));
      byte[] id = jsonArrayToBytes(record.optJSONArray("id"));
      byte[] payload = jsonArrayToBytes(record.optJSONArray("payload"));
      ndefRecords[i] = new NdefRecord(tnf, type, id, payload);
    }
    return new NdefMessage(ndefRecords);
  }

  static byte[] jsonArrayToBytes(JSONArray array) throws JSONException {
    if (array == null) {
      return new byte[0];
    }
    byte[] bytes = new byte[array.length()];
    for (int i = 0; i < array.length(); i++) {
      int value = array.getInt(i);
      bytes[i] = (byte) (value & 0xFF);
    }
    return bytes;
  }
}
