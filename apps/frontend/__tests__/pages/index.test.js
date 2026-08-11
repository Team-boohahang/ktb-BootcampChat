import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Login } from '../../pages/index';
import authService from '@/services/authService';

vi.mock('next/router', () => ({
  useRouter: () => ({
    query: {},
    push: vi.fn(),
  }),
}));

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({
    login: vi.fn(),
  }),
  withoutAuth: component => component,
}));

vi.mock('@/services/authService', () => ({
  default: {
    checkServerConnection: vi.fn(),
  },
}));

describe('Login', () => {
  it('keeps the login form available while the health check is pending', () => {
    authService.checkServerConnection.mockReturnValue(new Promise(() => {}));

    render(<Login />);

    expect(screen.getByTestId('login-email-input')).toBeEnabled();
    expect(screen.getByTestId('login-password-input')).toBeEnabled();
    expect(screen.getByTestId('login-submit-button')).toBeEnabled();
  });
});
