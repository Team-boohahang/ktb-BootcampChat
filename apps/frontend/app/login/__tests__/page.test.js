import { render, waitFor } from '@testing-library/react';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { replaceMock } = vi.hoisted(() => ({
  replaceMock: vi.fn(),
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: replaceMock }),
}));

import LoginRedirectPage from '../page';

describe('LoginRedirectPage', () => {
  beforeEach(() => {
    replaceMock.mockClear();
    window.history.replaceState({}, '', '/login');
  });

  it('클라이언트에서 로그인 화면으로 이동하면서 쿼리를 유지한다', async () => {
    window.history.replaceState({}, '', '/login?redirect=%2Fchat&source=e2e');

    render(React.createElement(LoginRedirectPage));

    await waitFor(() => {
      expect(replaceMock).toHaveBeenCalledWith('/?redirect=%2Fchat&source=e2e');
    });
  });
});
