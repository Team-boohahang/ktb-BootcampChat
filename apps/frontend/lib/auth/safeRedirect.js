const INTERNAL_ORIGIN = 'http://internal.local';

export const getSafeRedirectPath = (value, fallback = '/chat') => {
  if (typeof value !== 'string' || !value.startsWith('/')) {
    return fallback;
  }

  try {
    const url = new URL(value, INTERNAL_ORIGIN);

    if (url.origin !== INTERNAL_ORIGIN) {
      return fallback;
    }

    return `${url.pathname}${url.search}${url.hash}`;
  } catch {
    return fallback;
  }
};
