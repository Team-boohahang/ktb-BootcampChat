import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ChatPage from '../page';

const authState = vi.hoisted(() => ({
  isAuthenticated: false,
  isLoading: true,
}));

vi.mock('next/navigation', () => ({
  usePathname: () => '/chat',
  useRouter: () => ({ replace: vi.fn() }),
}));

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => authState,
}));

vi.mock('@/components/ChatHeader', () => ({
  default: () => <header>채팅 헤더</header>,
}));

vi.mock('@/features/chat/rooms/ChatRoomsView', () => ({
  default: () => <main>채팅방 목록 셸</main>,
}));

describe('ChatPage', () => {
  it('인증 정보를 복원하는 동안에도 채팅방 목록 셸을 렌더링한다', () => {
    render(<ChatPage />);

    expect(screen.getByText('채팅 헤더')).toBeInTheDocument();
    expect(screen.getByText('채팅방 목록 셸')).toBeInTheDocument();
    expect(screen.queryByText('Loading...')).not.toBeInTheDocument();
  });
});
