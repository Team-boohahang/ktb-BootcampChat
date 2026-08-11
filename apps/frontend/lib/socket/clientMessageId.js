const UUID_V4_PATTERN = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx';

export const createClientMessageId = () => {
  const cryptoApi = globalThis.crypto;

  if (typeof cryptoApi?.randomUUID === 'function') {
    return cryptoApi.randomUUID();
  }

  const randomBytes = new Uint8Array(32);
  if (typeof cryptoApi?.getRandomValues === 'function') {
    cryptoApi.getRandomValues(randomBytes);
  } else {
    for (let index = 0; index < randomBytes.length; index += 1) {
      randomBytes[index] = Math.floor(Math.random() * 256);
    }
  }

  let byteIndex = 0;
  return UUID_V4_PATTERN.replace(/[xy]/g, character => {
    const randomValue = randomBytes[byteIndex] & 0x0f;
    byteIndex += 1;
    const value = character === 'x' ? randomValue : (randomValue & 0x03) | 0x08;
    return value.toString(16);
  });
};

export default createClientMessageId;
