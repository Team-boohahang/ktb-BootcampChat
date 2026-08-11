import { describe, expect, it } from 'vitest';
import { getSafeRedirectPath } from '../safeRedirect';

describe('getSafeRedirectPath', () => {
  it.each([
    ['/chat', '/chat'],
    ['/chat/room-1?from=login#latest', '/chat/room-1?from=login#latest'],
  ])('allows an internal path: %s', (value, expected) => {
    expect(getSafeRedirectPath(value)).toBe(expected);
  });

  it.each([
    '//evil.example.com',
    'https://evil.example.com',
    '/\\evil.example.com',
    'javascript:alert(1)',
    '',
  ])('rejects an external or invalid redirect: %s', (value) => {
    expect(getSafeRedirectPath(value)).toBe('/chat');
  });

  it('rejects ambiguous non-string values', () => {
    expect(getSafeRedirectPath(['/chat', '//evil.example.com'])).toBe('/chat');
  });
});
