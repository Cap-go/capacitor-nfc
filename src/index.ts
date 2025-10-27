import { registerPlugin } from '@capacitor/core';

import type { CapacitorNfcPlugin } from './definitions';

const CapacitorNfc = registerPlugin<CapacitorNfcPlugin>('CapacitorNfc', {
  web: () => import('./web').then((m) => new m.CapacitorNfcWeb()),
});

export * from './definitions';
export { CapacitorNfc };
