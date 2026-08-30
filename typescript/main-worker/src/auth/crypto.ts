const AUTH_CODE_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
const textEncoder = new TextEncoder();

export function randomBytes(length: number): Uint8Array {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return bytes;
}

export function randomToken(): string {
  return bytesToBase64Url(randomBytes(32));
}

export function generateAuthCode(): string {
  let value = '';
  for (let index = 0; index < 8; index += 1) {
    let byte: number;
    do {
      byte = randomBytes(1)[0];
    } while (byte >= 252);
    value += AUTH_CODE_ALPHABET[byte % AUTH_CODE_ALPHABET.length];
  }
  return value;
}

export function formatAuthCode(code: string): string {
  return `${code.slice(0, 4)}-${code.slice(4)}`;
}

export async function digest(value: string): Promise<string> {
  return bytesToHex(await digestBytes(textEncoder.encode(value)));
}

export async function digestBytes(value: Uint8Array): Promise<Uint8Array> {
  const bytes = new Uint8Array(value);
  const result = await crypto.subtle.digest('SHA-256', bytes.buffer as ArrayBuffer);
  return new Uint8Array(result);
}

function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }

  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
}

export function bytesToBase32(bytes: Uint8Array): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let result = '';
  let buffer = 0;
  let bitCount = 0;

  for (const byte of bytes) {
    buffer = (buffer << 8) | byte;
    bitCount += 8;

    while (bitCount >= 5) {
      bitCount -= 5;
      result += alphabet[(buffer >> bitCount) & 0x1f];
      buffer &= (1 << bitCount) - 1;
    }
  }

  if (bitCount > 0) {
    result += alphabet[(buffer << (5 - bitCount)) & 0x1f];
  }

  return result;
}

export function constantTimeEqual(left: string, right: string): boolean {
  const leftBytes = textEncoder.encode(left);
  const rightBytes = textEncoder.encode(right);
  const length = Math.max(leftBytes.length, rightBytes.length);
  let difference = leftBytes.length ^ rightBytes.length;

  for (let index = 0; index < length; index += 1) {
    difference |= (leftBytes[index] ?? 0) ^ (rightBytes[index] ?? 0);
  }

  return difference === 0;
}
