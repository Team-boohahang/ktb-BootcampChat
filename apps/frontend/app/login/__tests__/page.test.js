import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useSearchParams: () => new URLSearchParams('redirect=%2Fchat'),
}));

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ login: vi.fn() }),
}));

vi.mock('@/services/authService', () => ({
  default: { checkServerConnection: vi.fn() },
}));

import LoginRoutePage from '../page';

describe('LoginRoutePage', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/login?redirect=%2Fchat');
  });

  it('문서 이동 없이 루트 URL에서 로그인 폼을 유지한다', async () => {
    render(React.createElement(LoginRoutePage));

    expect(screen.getByTestId('login-email-input')).toBeEnabled();
    expect(screen.getByTestId('login-password-input')).toBeEnabled();
    expect(screen.getByTestId('login-submit-button')).toBeEnabled();

    await waitFor(() => {
      expect(window.location.pathname).toBe('/');
      expect(window.location.search).toBe('?redirect=%2Fchat');
    });
  });
});
