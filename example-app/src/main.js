import { CapacitorUpdater } from '@capgo/capacitor-updater';
import { Capacitor } from '@capacitor/core';
import './style.css';
import { CapacitorNfc } from '@capgo/capacitor-nfc';

const statusIndicator = document.getElementById('status-indicator');
const sessionIndicator = document.getElementById('session-indicator');
const logOutput = document.getElementById('log-output');
const startButton = document.getElementById('start-scan');
const stopButton = document.getElementById('stop-scan');
const clearButton = document.getElementById('clear-log');
const writeButton = document.getElementById('write-text');
const textInput = document.getElementById('text-input');
const langInput = document.getElementById('lang-input');

let sessionActive = false;
const logBuffer = [];
const LOG_LIMIT = 40;

function appendLog(message, data) {
  const timestamp = new Date().toLocaleTimeString();
  const formatted = data
    ? `${timestamp}  ${message}\n${JSON.stringify(data, null, 2)}`
    : `${timestamp}  ${message}`;
  logBuffer.unshift(formatted);
  if (logBuffer.length > LOG_LIMIT) {
    logBuffer.pop();
  }
  logOutput.textContent = logBuffer.join('\n\n');
}

function updateStatusIndicator(status) {
  statusIndicator.classList.remove('status-active', 'status-inactive', 'status-unknown');
  switch (status) {
    case 'NFC_OK':
      statusIndicator.classList.add('status-active');
      statusIndicator.textContent = 'Enabled';
      break;
    case 'NFC_DISABLED':
      statusIndicator.classList.add('status-inactive');
      statusIndicator.textContent = 'Disabled';
      break;
    case 'NO_NFC':
      statusIndicator.classList.add('status-inactive');
      statusIndicator.textContent = 'Unavailable';
      break;
    default:
      statusIndicator.classList.add('status-unknown');
      statusIndicator.textContent = status ?? 'Unknown';
      break;
  }
}

function updateSessionIndicator(active) {
  sessionIndicator.classList.remove('status-active', 'status-inactive', 'status-unknown');
  if (active) {
    sessionIndicator.classList.add('status-active');
    sessionIndicator.textContent = 'Scanning';
  } else {
    sessionIndicator.classList.add('status-inactive');
    sessionIndicator.textContent = 'Idle';
  }
}

function encodeTextRecord(text, languageCode) {
  const encoder = new TextEncoder();
  const lang = (languageCode || 'en').trim().toLowerCase().slice(0, 8);
  const langBytes = encoder.encode(lang);
  const textBytes = encoder.encode(text);

  if (langBytes.length > 0x3f) {
    throw new Error('Language code must be at most 63 bytes.');
  }

  const payload = new Uint8Array(1 + langBytes.length + textBytes.length);
  payload[0] = langBytes.length & 0x3f; // UTF-8 encoding with language length
  payload.set(langBytes, 1);
  payload.set(textBytes, 1 + langBytes.length);

  return {
    tnf: 0x01,
    type: [0x54], // 'T'
    id: [],
    payload: Array.from(payload),
  };
}

async function refreshStatus() {
  try {
    const { status } = await CapacitorNfc.getStatus();
    updateStatusIndicator(status);
  } catch (error) {
    appendLog('⚠️ Failed to load status', error);
    updateStatusIndicator('Unknown');
  }
}

startButton.addEventListener('click', async () => {
  try {
    await CapacitorNfc.startScanning({
      invalidateAfterFirstRead: false,
      alertMessage: 'Hold an NFC tag near the top of your device.',
    });
    sessionActive = true;
    updateSessionIndicator(true);
    appendLog('✅ Started scanning');
  } catch (error) {
    appendLog('❌ Unable to start scanning', error);
  }
});

stopButton.addEventListener('click', async () => {
  try {
    await CapacitorNfc.stopScanning();
    sessionActive = false;
    updateSessionIndicator(false);
    appendLog('🛑 Scanning stopped');
  } catch (error) {
    appendLog('❌ Unable to stop scanning', error);
  }
});

clearButton.addEventListener('click', () => {
  logBuffer.length = 0;
  logOutput.textContent = 'Waiting for events…';
});

writeButton.addEventListener('click', async () => {
  const text = textInput.value.trim();
  const lang = langInput.value.trim() || 'en';

  if (!text) {
    appendLog('⚠️ Provide text before writing a record.');
    return;
  }

  try {
    const record = encodeTextRecord(text, lang);
    await CapacitorNfc.write({
      allowFormat: true,
      records: [record],
    });
    appendLog('✅ Text record written to tag.');
  } catch (error) {
    appendLog('❌ Failed to write tag', error);
  }
});

CapacitorNfc.addListener('nfcEvent', async (event) => {
  sessionActive = true;
  updateSessionIndicator(true);
  appendLog('📡 Tag discovered', event);

  // Stop scanning after reading the tag
  try {
    await CapacitorNfc.stopScanning();
    sessionActive = false;
    updateSessionIndicator(false);
    appendLog('🛑 Scanning stopped after reading tag');
  } catch (error) {
    appendLog('⚠️ Failed to stop scanning after reading tag', error);
  }
});

CapacitorNfc.addListener('nfcStateChange', (event) => {
  updateStatusIndicator(event?.status);
  appendLog('⚙️ Adapter state changed', event);
});

(async () => {
  await refreshStatus();
  try {
    const { version } = await CapacitorNfc.getPluginVersion();
    appendLog(`ℹ️ Using native plugin version ${version}`);
  } catch (error) {
    appendLog('⚠️ Unable to read plugin version', error);
  }
  try {
    const { supported } = await CapacitorNfc.isSupported();
    if (supported) {
      appendLog('✅ NFC hardware is available on this device');
    } else {
      appendLog('⚠️ NFC hardware is not available on this device');
    }
  } catch (error) {
    appendLog('⚠️ Unable to check NFC hardware support', error);
  }
})();

if (Capacitor.isNativePlatform()) {
  CapacitorUpdater.notifyAppReady().catch((error) => {
    console.error('Capgo notifyAppReady failed', error);
  });
}
