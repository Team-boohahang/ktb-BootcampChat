import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Login } from '../../pages/index';
import authService from '@/services/authService';

const { loginMock, routerMock } = vi.hoisted(() => ({
  loginMock: vi.fn(),
  routerMock: {
    query: {},
    push: vi.fn(),
  },
}));

vi.mock('next/router', () => ({
  useRouter: () => routerMock,
}));

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({
    login: loginMock,
  }),
  withoutAuth: component => component,
}));

vi.mock('@/services/authService', () => ({
  default: {
    checkServerConnection: vi.fn(),
  },
}));

describe('Login', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    routerMock.query = {};
    loginMock.mockResolvedValue(undefined);
  });

  it('keeps the login form available while the health check is pending', () => {
    authService.checkServerConnection.mockReturnValue(new Promise(() => {}));

    render(<Login />);

    expect(screen.getByTestId('login-email-input')).toBeEnabled();
    expect(screen.getByTestId('login-password-input')).toBeEnabled();
    expect(screen.getByTestId('login-submit-button')).toBeEnabled();
  });

  it.each([
    [['/chat/a', '/chat/b']],
    ['https://evil.example.com'],
    ['//evil.example.com'],
  ])('falls back for an unsafe Pages Router redirect: %j', async (redirect) => {
    routerMock.query = { redirect };
    render(<Login />);

    fireEvent.change(screen.getByTestId('login-email-input'), {
      target: { value: 'user@example.com' },
    });
    fireEvent.change(screen.getByTestId('login-password-input'), {
      target: { value: 'Password123!' },
    });
    fireEvent.click(screen.getByTestId('login-submit-button'));

    await waitFor(() => {
      expect(loginMock).toHaveBeenCalledOnce();
      expect(routerMock.push).toHaveBeenCalledWith('/chat');
    });
  });
});
