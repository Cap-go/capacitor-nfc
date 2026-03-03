import type { PluginListenerHandle } from '@capacitor/core';

/**
 * Possible NFC adapter states returned by {@link CapacitorNfcPlugin.getStatus}.
 *
 * Matches the constants provided by the original PhoneGap NFC plugin for
 * compatibility with existing applications.
 */
export type NfcStatus = 'NFC_OK' | 'NO_NFC' | 'NFC_DISABLED' | 'NDEF_PUSH_DISABLED';

/**
 * Event type describing the kind of NFC discovery that happened.
 *
 * - `tag`: A generic NFC tag (no NDEF payload).
 * - `ndef`: A tag exposing an NDEF payload.
 * - `ndef-mime`: An NDEF tag that matched one of the MIME type filters.
 * - `ndef-formatable`: A tag that can be formatted to NDEF.
 */
export type NfcEventType = 'tag' | 'ndef' | 'ndef-mime' | 'ndef-formatable';

/**
 * iOS NFC polling option types
 * These correspond to NFCTagReaderSession.PollingOption in CoreNFC
 */
export type IosPollingOption = 'iso14443' | 'iso15693' | 'iso18092';

/**
 * JSON structure representing a single NDEF record.
 *
 * Mirrors the data format returned by the legacy Cordova implementation and
 * uses integer arrays instead of strings to preserve the original payload
 * bytes.
 */
export interface NdefRecord {
  /**
   * Type Name Format identifier.
   */
  tnf: number;
  /**
   * Type field expressed as an array of byte values.
   */
  type: number[];
  /**
   * Record identifier expressed as an array of byte values.
   */
  id: number[];
  /**
   * Raw payload expressed as an array of byte values.
   */
  payload: number[];
}

/**
 * Representation of the full tag information returned by the native layers.
 *
 * Supports standard NFC Forum tags as well as MIFARE Ultralight cards (including
 * EV1 and NTAG variants). NDEF data is automatically extracted from MIFARE Ultralight
 * tags when available.
 */
export interface NfcTag {
  /**
   * Raw identifier bytes for the tag.
   */
  id?: number[];
  /**
   * List of Android tech strings (e.g. `android.nfc.tech.Ndef`).
   */
  techTypes?: string[];
  /**
   * Human readable tag type when available (e.g. `NFC Forum Type 2`, `MIFARE Ultralight`).
   */
  type?: string | null;
  /**
   * Maximum writable size in bytes for tags that expose NDEF information.
   */
  maxSize?: number | null;
  /**
   * Indicates whether the tag can be written to.
   */
  isWritable?: boolean | null;
  /**
   * Indicates whether the tag can be permanently locked.
   */
  canMakeReadOnly?: boolean | null;
  /**
   * Array of NDEF records discovered on the tag.
   */
  ndefMessage?: NdefRecord[] | null;
}

/**
 * Generic NFC discovery event dispatched by the plugin.
 */
export interface NfcEvent {
  type: NfcEventType;
  tag: NfcTag;
}

/**
 * Options controlling the behaviour of {@link CapacitorNfcPlugin.startScanning}.
 */
export interface StartScanningOptions {
  /**
   * iOS-only: closes the NFC session automatically after the first successful tag read.
   * Defaults to `true`.
   */
  invalidateAfterFirstRead?: boolean;
  /**
   * iOS-only: custom message displayed in the NFC system sheet while scanning.
   */
  alertMessage?: string;
  /**
   * iOS-only: session type to use for NFC scanning.
   * - `'ndef'`: Uses NFCNDEFReaderSession (default). Only detects NDEF-formatted tags.
   * - `'tag'`: Uses NFCTagReaderSession. Detects both NDEF and non-NDEF tags (e.g., raw MIFARE tags).
   *            Allows reading UID from unformatted tags.
   * Defaults to `'ndef'` for backward compatibility.
   */
  iosSessionType?: 'ndef' | 'tag';
  /**
   * Android-only: raw flags passed to `NfcAdapter.enableReaderMode`.
   * Defaults to enabling all tag types with skipping NDEF checks.
   */
  androidReaderModeFlags?: number;
  /**
   * iOS-only: Array of polling options to use when scanning for NFC tags.
   *
   * Available options:
   * - `'iso14443'`: NFC-A and NFC-B protocols (MIFARE, ISO-DEP, etc.)
   * - `'iso15693'`: NFC-V protocol (vicinity cards)
   * - `'iso18092'`: NFC-F protocol (FeliCa) - may require additional entitlements
   *
   * Defaults to `['iso14443', 'iso15693']` if not specified.
   *
   * Note: `iso18092` (FeliCa) may require specific app entitlements and could
   * cause issues if those entitlements are not configured. Use with caution.
   *
   * @example
   * // Default scanning (most compatible)
   * await startScanning({ iosPollingOptions: ['iso14443', 'iso15693'] });
   *
   * // Include FeliCa support (requires entitlements)
   * await startScanning({ iosPollingOptions: ['iso14443', 'iso15693', 'iso18092'] });
   *
   * // Only scan for ISO 15693 tags
   * await startScanning({ iosPollingOptions: ['iso15693'] });
   */
  iosPollingOptions?: IosPollingOption[];
}

/**
 * Options used when writing an NDEF message on the current tag.
 */
export interface WriteTagOptions {
  /**
   * Array of records that compose the NDEF message to be written.
   */
  records: NdefRecord[];
  /**
   * When `true`, the plugin attempts to format NDEF-formattable tags before writing.
   * Defaults to `true`.
   */
  allowFormat?: boolean;
}

/**
 * Options used when sharing an NDEF message with another device using Android Beam / P2P mode.
 */
export interface ShareTagOptions {
  records: NdefRecord[];
}

/**
 * Event emitted whenever the NFC adapter availability changes.
 */
export interface NfcStateChangeEvent {
  status: NfcStatus;
  enabled: boolean;
}

/**
 * Public API surface for the Capacitor NFC plugin.
 *
 * The interface intentionally mirrors the behaviour of the reference PhoneGap
 * implementation to ease migration while embracing idiomatic Capacitor APIs.
 */
export interface CapacitorNfcPlugin {
  /**
   * Starts listening for NFC tags.
   */
  startScanning(options?: StartScanningOptions): Promise<void>;
  /**
   * Stops the ongoing NFC scanning session.
   */
  stopScanning(): Promise<void>;
  /**
   * Writes the provided NDEF records to the last discovered tag.
   */
  write(options: WriteTagOptions): Promise<void>;
  /**
   * Attempts to erase the last discovered tag by writing an empty NDEF message.
   */
  erase(): Promise<void>;
  /**
   * Attempts to make the last discovered tag read-only.
   */
  makeReadOnly(): Promise<void>;
  /**
   * Shares an NDEF message with another device via peer-to-peer (Android only).
   */
  share(options: ShareTagOptions): Promise<void>;
  /**
   * Stops sharing previously provided NDEF message (Android only).
   */
  unshare(): Promise<void>;
  /**
   * Returns the current NFC adapter status.
   */
  getStatus(): Promise<{ status: NfcStatus }>;
  /**
   * Opens the system settings page where the user can enable NFC.
   */
  showSettings(): Promise<void>;
  /**
   * Returns the version string baked into the native plugin.
   */
  getPluginVersion(): Promise<{ version: string }>;
  /**
   * Checks whether the device has NFC hardware support.
   *
   * Returns `true` if NFC hardware is present on the device, regardless of
   * whether NFC is currently enabled or disabled. Returns `false` if the
   * device does not have NFC hardware.
   *
   * Use this method to determine if NFC features should be shown in your
   * app's UI. To check if NFC is currently enabled, use `getStatus()`.
   */
  isSupported(): Promise<{ supported: boolean }>;

  addListener(eventName: 'nfcEvent', listenerFunc: (event: NfcEvent) => void): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'tagDiscovered' | 'ndefDiscovered' | 'ndefMimeDiscovered' | 'ndefFormatableDiscovered',
    listenerFunc: (event: NfcEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'nfcStateChange',
    listenerFunc: (event: NfcStateChangeEvent) => void,
  ): Promise<PluginListenerHandle>;
}

export type { PluginListenerHandle } from '@capacitor/core';
