import React from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProviderWithRouter, useAuth } from '../AuthContext';
import socketService from '../../services/socket';
import authService from '../../services/authService';
import {
  clearStoredUser,
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
});
