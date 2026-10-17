// @ts-nocheck: This DOM-focused script relies on dynamic element lookups that are not typed yet.
/// <reference lib="dom" />

import './styles.css';
import { markBundledUi } from './build-metadata.ts';

markBundledUi(document.documentElement);

const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
function randomCharacter() {
  const bytes = new Uint8Array(1);
  do {
    crypto.getRandomValues(bytes);
  } while (bytes[0] >= 252);
  return alphabet[bytes[0] % alphabet.length];
}

const code = Array.from({ length: 8 }, randomCharacter);
const authCode = code.slice(0, 4).join('') + '-' + code.slice(4).join('');
document.querySelector('#command').textContent = '/cobbleware auth ' + authCode;

async function poll() {
  try {
    const response = await fetch('/playerapi/v1/authenticate', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: authCode }),
    });
    if (response.status === 201) {
      globalThis.location.assign('/index.html');
      return;
    }
  } catch {
    // Retry on the next interval if the Runtime is temporarily unavailable.
  }
  globalThis.setTimeout(poll, 1000);
}

poll();
