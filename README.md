# @capgo/capacitor-nfc
 <a href="https://capgo.app/"><img src='https://raw.githubusercontent.com/Cap-go/capgo/main/assets/capgo_banner.png' alt='Capgo - Instant updates for capacitor'/></a>

<div align="center">
  <h2><a href="https://capgo.app/?ref=plugin_nfc"> ➡️ Get Instant updates for your App with Capgo</a></h2>
  <h2><a href="https://capgo.app/consulting/?ref=plugin_nfc"> Missing a feature? We’ll build the plugin for you 💪</a></h2>
</div>

Native NFC tag detection, reading, and writing for Capacitor apps on iOS and Android.

Modern Capacitor port of the battle-tested [phonegap-nfc](https://github.com/chariotsolutions/phonegap-nfc) plugin, aligned with Capgo conventions and tooling.

## Supported Tag Types

- **Standard NFC Forum Tags** (Type 1-4)
- **MIFARE Ultralight** - Full support for reading NDEF data from MIFARE Ultralight tags, including EV1 and NTAG variants. The plugin automatically detects MIFARE Ultralight cards and extracts NDEF messages in addition to standard NFC tags.

## Documentation

The most complete documentation will live on the Capgo docs portal. Until then, explore the TypeScript definitions (`src/definitions.ts`) and run the included example app for a tour of the API.

## Install

```bash
npm install @capgo/capacitor-nfc
npx cap sync
```

Remember to add the required platform configuration:

- **Android:** ensure your `AndroidManifest.xml` declares the `android.permission.NFC` permission.
- **iOS:** add `NFCReaderUsageDescription` to your app `Info.plist` to explain why NFC access is needed.

## Usage

```ts
import { CapacitorNfc } from '@capgo/capacitor-nfc';

await CapacitorNfc.startScanning({
  invalidateAfterFirstRead: false, // keep the session open so we can write later
  alertMessage: 'Hold a tag near the top of your device.',
});

const listener = await CapacitorNfc.addListener('nfcEvent', (event) => {
  console.info('Tag type:', event.type);
  console.info('First record:', event.tag?.ndefMessage?.[0]);
});

// Later, write a simple text record back to the tag
const encoder = new TextEncoder();
const langBytes = Array.from(encoder.encode('en'));
const textBytes = Array.from(encoder.encode('Hello Capgo'));
const payload = [langBytes.length & 0x3f, ...langBytes, ...textBytes];

await CapacitorNfc.write({
  allowFormat: true,
  records: [
    {
      tnf: 0x01,
      type: [0x54], // 'T'
      id: [],
      payload,
    },
  ],
});

await listener.remove();
await CapacitorNfc.stopScanning();
```

## API

<docgen-index>

* [`startScanning(...)`](#startscanning)
* [`stopScanning()`](#stopscanning)
* [`write(...)`](#write)
* [`erase()`](#erase)
* [`makeReadOnly()`](#makereadonly)
* [`share(...)`](#share)
* [`unshare()`](#unshare)
* [`getStatus()`](#getstatus)
* [`showSettings()`](#showsettings)
* [`getPluginVersion()`](#getpluginversion)
* [`addListener('nfcEvent', ...)`](#addlistenernfcevent-)
* [`addListener('tagDiscovered' | 'ndefDiscovered' | 'ndefMimeDiscovered' | 'ndefFormatableDiscovered', ...)`](#addlistenertagdiscovered--ndefdiscovered--ndefmimediscovered--ndefformatablediscovered-)
* [`addListener('nfcStateChange', ...)`](#addlistenernfcstatechange-)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

Public API surface for the Capacitor NFC plugin.

The interface intentionally mirrors the behaviour of the reference PhoneGap
implementation to ease migration while embracing idiomatic Capacitor APIs.

### startScanning(...)

```typescript
startScanning(options?: StartScanningOptions | undefined) => Promise<void>
```

Starts listening for NFC tags.

| Param         | Type                                                                  |
| ------------- | --------------------------------------------------------------------- |
| **`options`** | <code><a href="#startscanningoptions">StartScanningOptions</a></code> |

--------------------


### stopScanning()

```typescript
stopScanning() => Promise<void>
```

Stops the ongoing NFC scanning session.

--------------------


### write(...)

```typescript
write(options: WriteTagOptions) => Promise<void>
```

Writes the provided NDEF records to the last discovered tag.

| Param         | Type                                                        |
| ------------- | ----------------------------------------------------------- |
| **`options`** | <code><a href="#writetagoptions">WriteTagOptions</a></code> |

--------------------


### erase()

```typescript
erase() => Promise<void>
```

Attempts to erase the last discovered tag by writing an empty NDEF message.

--------------------


### makeReadOnly()

```typescript
makeReadOnly() => Promise<void>
```

Attempts to make the last discovered tag read-only.

--------------------


### share(...)

```typescript
share(options: ShareTagOptions) => Promise<void>
```

Shares an NDEF message with another device via peer-to-peer (Android only).

| Param         | Type                                                        |
| ------------- | ----------------------------------------------------------- |
| **`options`** | <code><a href="#sharetagoptions">ShareTagOptions</a></code> |

--------------------


### unshare()

```typescript
unshare() => Promise<void>
```

Stops sharing previously provided NDEF message (Android only).

--------------------


### getStatus()

```typescript
getStatus() => Promise<{ status: NfcStatus; }>
```

Returns the current NFC adapter status.

**Returns:** <code>Promise&lt;{ status: <a href="#nfcstatus">NfcStatus</a>; }&gt;</code>

--------------------


### showSettings()

```typescript
showSettings() => Promise<void>
```

Opens the system settings page where the user can enable NFC.

--------------------


### getPluginVersion()

```typescript
getPluginVersion() => Promise<{ version: string; }>
```

Returns the version string baked into the native plugin.

**Returns:** <code>Promise&lt;{ version: string; }&gt;</code>

--------------------


### addListener('nfcEvent', ...)

```typescript
addListener(eventName: 'nfcEvent', listenerFunc: (event: NfcEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                              |
| ------------------ | ----------------------------------------------------------------- |
| **`eventName`**    | <code>'nfcEvent'</code>                                           |
| **`listenerFunc`** | <code>(event: <a href="#nfcevent">NfcEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('tagDiscovered' | 'ndefDiscovered' | 'ndefMimeDiscovered' | 'ndefFormatableDiscovered', ...)

```typescript
addListener(eventName: 'tagDiscovered' | 'ndefDiscovered' | 'ndefMimeDiscovered' | 'ndefFormatableDiscovered', listenerFunc: (event: NfcEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                                   |
| ------------------ | ------------------------------------------------------------------------------------------------------ |
| **`eventName`**    | <code>'tagDiscovered' \| 'ndefDiscovered' \| 'ndefMimeDiscovered' \| 'ndefFormatableDiscovered'</code> |
| **`listenerFunc`** | <code>(event: <a href="#nfcevent">NfcEvent</a>) =&gt; void</code>                                      |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### addListener('nfcStateChange', ...)

```typescript
addListener(eventName: 'nfcStateChange', listenerFunc: (event: NfcStateChangeEvent) => void) => Promise<PluginListenerHandle>
```

| Param              | Type                                                                                    |
| ------------------ | --------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'nfcStateChange'</code>                                                           |
| **`listenerFunc`** | <code>(event: <a href="#nfcstatechangeevent">NfcStateChangeEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

--------------------


### Interfaces


#### StartScanningOptions

Options controlling the behaviour of {@link CapacitorNfcPlugin.startScanning}.

| Prop                           | Type                 | Description                                                                                                                    |
| ------------------------------ | -------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| **`invalidateAfterFirstRead`** | <code>boolean</code> | iOS-only: closes the NFC session automatically after the first successful tag read. Defaults to `true`.                        |
| **`alertMessage`**             | <code>string</code>  | iOS-only: custom message displayed in the NFC system sheet while scanning.                                                     |
| **`androidReaderModeFlags`**   | <code>number</code>  | Android-only: raw flags passed to `NfcAdapter.enableReaderMode`. Defaults to enabling all tag types with skipping NDEF checks. |


#### WriteTagOptions

Options used when writing an NDEF message on the current tag.

| Prop              | Type                      | Description                                                                                          |
| ----------------- | ------------------------- | ---------------------------------------------------------------------------------------------------- |
| **`records`**     | <code>NdefRecord[]</code> | Array of records that compose the NDEF message to be written.                                        |
| **`allowFormat`** | <code>boolean</code>      | When `true`, the plugin attempts to format NDEF-formattable tags before writing. Defaults to `true`. |


#### NdefRecord

JSON structure representing a single NDEF record.

Mirrors the data format returned by the legacy Cordova implementation and
uses integer arrays instead of strings to preserve the original payload
bytes.

| Prop          | Type                  | Description                                             |
| ------------- | --------------------- | ------------------------------------------------------- |
| **`tnf`**     | <code>number</code>   | Type Name Format identifier.                            |
| **`type`**    | <code>number[]</code> | Type field expressed as an array of byte values.        |
| **`id`**      | <code>number[]</code> | Record identifier expressed as an array of byte values. |
| **`payload`** | <code>number[]</code> | Raw payload expressed as an array of byte values.       |


#### ShareTagOptions

Options used when sharing an NDEF message with another device using Android Beam / P2P mode.

| Prop          | Type                      |
| ------------- | ------------------------- |
| **`records`** | <code>NdefRecord[]</code> |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### NfcEvent

Generic NFC discovery event dispatched by the plugin.

| Prop       | Type                                                  |
| ---------- | ----------------------------------------------------- |
| **`type`** | <code><a href="#nfceventtype">NfcEventType</a></code> |
| **`tag`**  | <code><a href="#nfctag">NfcTag</a></code>             |


#### NfcTag

Representation of the full tag information returned by the native layers.

Supports standard NFC Forum tags as well as MIFARE Ultralight cards (including
EV1 and NTAG variants). NDEF data is automatically extracted from MIFARE Ultralight
tags when available.

| Prop                  | Type                              | Description                                                                            |
| --------------------- | --------------------------------- | -------------------------------------------------------------------------------------- |
| **`id`**              | <code>number[]</code>             | Raw identifier bytes for the tag.                                                      |
| **`techTypes`**       | <code>string[]</code>             | List of Android tech strings (e.g. `android.nfc.tech.Ndef`).                           |
| **`type`**            | <code>string \| null</code>       | Human readable tag type when available (e.g. `NFC Forum Type 2`, `MIFARE Ultralight`). |
| **`maxSize`**         | <code>number \| null</code>       | Maximum writable size in bytes for tags that expose NDEF information.                  |
| **`isWritable`**      | <code>boolean \| null</code>      | Indicates whether the tag can be written to.                                           |
| **`canMakeReadOnly`** | <code>boolean \| null</code>      | Indicates whether the tag can be permanently locked.                                   |
| **`ndefMessage`**     | <code>NdefRecord[] \| null</code> | Array of NDEF records discovered on the tag.                                           |


#### NfcStateChangeEvent

Event emitted whenever the NFC adapter availability changes.

| Prop          | Type                                            |
| ------------- | ----------------------------------------------- |
| **`status`**  | <code><a href="#nfcstatus">NfcStatus</a></code> |
| **`enabled`** | <code>boolean</code>                            |


### Type Aliases


#### NfcStatus

Possible NFC adapter states returned by {@link CapacitorNfcPlugin.getStatus}.

Matches the constants provided by the original PhoneGap NFC plugin for
compatibility with existing applications.

<code>'NFC_OK' | 'NO_NFC' | 'NFC_DISABLED' | 'NDEF_PUSH_DISABLED'</code>


#### NfcEventType

Event type describing the kind of NFC discovery that happened.

- `tag`: A generic NFC tag (no NDEF payload).
- `ndef`: A tag exposing an NDEF payload.
- `ndef-mime`: An NDEF tag that matched one of the MIME type filters.
- `ndef-formatable`: A tag that can be formatted to NDEF.

<code>'tag' | 'ndef' | 'ndef-mime' | 'ndef-formatable'</code>

</docgen-api>
