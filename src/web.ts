import { WebPlugin } from '@capacitor/core';

import type {
  CapacitorNfcPlugin,
  NfcStateChangeEvent,
  NfcEvent,
  ShareTagOptions,
  StartScanningOptions,
  WriteTagOptions,
  PluginListenerHandle,
} from './definitions';

export class CapacitorNfcWeb extends WebPlugin implements CapacitorNfcPlugin {
  private unsupported(method: string): never {
    throw this.unimplemented(`CapacitorNfc.${method} is not available in a browser environment.`);
  }

  async startScanning(_options?: StartScanningOptions): Promise<void> {
    this.unsupported('startScanning');
  }

  async stopScanning(): Promise<void> {
    this.unsupported('stopScanning');
  }

  async write(_options: WriteTagOptions): Promise<void> {
    this.unsupported('write');
  }

  async erase(): Promise<void> {
    this.unsupported('erase');
  }

  async makeReadOnly(): Promise<void> {
    this.unsupported('makeReadOnly');
  }

  async share(_options: ShareTagOptions): Promise<void> {
    this.unsupported('share');
  }

  async unshare(): Promise<void> {
    this.unsupported('unshare');
  }

  async getStatus(): Promise<{ status: 'NO_NFC' }> {
    return { status: 'NO_NFC' };
  }

  async showSettings(): Promise<void> {
    this.unsupported('showSettings');
  }

  async getPluginVersion(): Promise<{ version: string }> {
    return { version: '0.0.0-web' };
  }

  addListener(
    eventName: 'nfcEvent',
    listenerFunc: (event: NfcEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'tagDiscovered' | 'ndefDiscovered' | 'ndefMimeDiscovered' | 'ndefFormatableDiscovered',
    listenerFunc: (event: NfcEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'nfcStateChange',
    listenerFunc: (event: NfcStateChangeEvent) => void,
  ): Promise<PluginListenerHandle>;
  async addListener(eventName: string, _listenerFunc: (..._args: any[]) => any): Promise<PluginListenerHandle> {
    this.unsupported(`addListener(${eventName})`);
  }
}
