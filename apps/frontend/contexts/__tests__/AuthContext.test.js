import React from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProviderWithRouter, useAuth } from '../AuthContext';
import socketService from '../../services/socket';
import authService from '../../services/authService';
import api from '../../lib/api/client';
import {
  clearStoredUser,
  getLastTokenVerification,
  loadStoredUser,
} from '../../lib/auth/authStorage';

vi.mock('../../services/socket', () => ({
  default: {
    disconnect: vi.fn(),
  },
}));

vi.mock('../../services/authService', () => ({
  default: {
    logout: vi.fn(),
  },
}));

vi.mock('../../lib/api/client', () => ({
  default: {
    post: vi.fn(),
  },
  getAuthHeaders: vi.fn(),
}));

vi.mock('../../lib/auth/authStorage', () => ({
  clearStoredUser: vi.fn(),
  getLastTokenVerification: vi.fn(),
  loadStoredUser: vi.fn(),
  saveLastTokenVerification: vi.fn(),
  saveStoredUser: vi.fn(user => user),
}));

describe('AuthContext logout', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authService.logout.mockResolvedValue(undefined);
    getLastTokenVerification.mockReturnValue(null);
    loadStoredUser.mockReturnValue({
      id: 'user-1',
      token: 'token',
      sessionId: 'session',
    });
  });

  it('starts local cleanup and navigation before the server logout completes', async () => {
    let resolveServerLogout;
    authService.logout.mockReturnValue(new Promise(resolve => {
      resolveServerLogout = resolve;
    }));
    const router = {
      push: vi.fn().mockResolvedValue(true),
      replace: vi.fn().mockResolvedValue(true),
    };
    const wrapper = ({ children }) => React.createElement(
      AuthProviderWithRouter,
      { router },
      children
    );
    const { result } = renderHook(() => useAuth(), { wrapper });

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    let logoutPromise;
    act(() => {
      logoutPromise = result.current.logout();
    });

    expect(authService.logout).toHaveBeenCalledWith('token', 'session');
    expect(socketService.disconnect).toHaveBeenCalledOnce();
    expect(clearStoredUser).toHaveBeenCalledOnce();
    expect(router.push).toHaveBeenCalledWith('/');

    resolveServerLogout();
    await act(async () => {
      await logoutPromise;
    });
  });

  it('does not reject logout when navigation fails', async () => {
    const navigationError = new Error('navigation failed');
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const router = {
      push: vi.fn().mockRejectedValue(navigationError),
      replace: vi.fn().mockResolvedValue(true),
    };
    const wrapper = ({ children }) => React.createElement(
      AuthProviderWithRouter,
      { router },
      children
    );
    const { result } = renderHook(() => useAuth(), { wrapper });

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    await expect(result.current.logout()).resolves.toBeUndefined();
    expect(clearStoredUser).toHaveBeenCalledOnce();
    expect(consoleError).toHaveBeenCalledWith(
      'Logout navigation error:',
      navigationError
    );

    consoleError.mockRestore();
  });

  it('preserves the session-expired error when logout navigation fails', async () => {
    const unauthorizedError = Object.assign(new Error('unauthorized'), {
      response: { status: 401 },
    });
    api.post
      .mockRejectedValueOnce(unauthorizedError)
      .mockRejectedValueOnce(new Error('refresh failed'));
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const router = {
      push: vi.fn().mockRejectedValue(new Error('navigation failed')),
      replace: vi.fn().mockResolvedValue(true),
    };
    const wrapper = ({ children }) => React.createElement(
      AuthProviderWithRouter,
      { router },
      children
    );
    const { result } = renderHook(() => useAuth(), { wrapper });

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    await expect(result.current.verifyToken()).rejects.toThrow(
      '세션이 만료되었습니다. 다시 로그인해주세요.'
    );
    expect(router.push).toHaveBeenCalledWith('/');

    consoleError.mockRestore();
  });
});
