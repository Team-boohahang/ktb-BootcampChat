import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ChatRoomView from '../ChatRoomView';

const mocks = vi.hoisted(() => ({
  chatRoom: {
    room: { _id: 'room-1', name: '테스트방', participants: [] },
    messages: [],
    connected: false,
    connectionStatus: 'disconnected',
    messageLoadError: null,
    retryMessageLoad: vi.fn(),
    currentUser: { _id: 'user-1', name: 'Tester' },
    fileInputRef: { current: null },
    handleMessageSubmit: vi.fn(),
    loading: false,
    error: null,
    handleReactionAdd: vi.fn(),
    handleReactionRemove: vi.fn(),
    loadingMessages: false,
    hasMoreMessages: false,
    handleLoadMore: vi.fn(),
  },
}));

vi.mock('../useChatRoom', () => ({
  useChatRoom: () => mocks.chatRoom,
}));

vi.mock('@/components/ChatRoomInfo', () => ({
  default: ({ connectionStatus }) => <div>room info: {connectionStatus}</div>,
}));

vi.mock('@/components/ChatMessages', () => ({
  default: () => <div>chat messages</div>,
}));

vi.mock('@/components/ChatInput', () => ({
  default: () => <div>chat input</div>,
}));

describe('ChatRoomView', () => {
  it('keeps messages visible while disconnected and defers to the status badge', () => {
    render(<ChatRoomView roomId="room-1" onNavigate={vi.fn()} onReplace={vi.fn()} asPath="/chat/room-1" />);

    // 재연결은 복구 가능한 상태다. 메시지를 유지하고 상태는 배지에 맡긴다.
    expect(screen.getByText('chat messages')).toBeInTheDocument();
    expect(screen.getByText('room info: disconnected')).toBeInTheDocument();
    expect(screen.queryByText(/연결이 끊어졌습니다/)).not.toBeInTheDocument();
  });

  it('uses the same viewport-height shell while the room is loading', () => {
    mocks.chatRoom = {
      ...mocks.chatRoom,
      room: null,
      loading: true,
    };

    render(<ChatRoomView roomId="room-1" onNavigate={vi.fn()} onReplace={vi.fn()} asPath="/chat/room-1" />);

    expect(screen.getByTestId('chat-room-shell')).toHaveStyle({
      height: 'calc(100dvh - 80px)',
      minHeight: '0',
    });
    expect(screen.getByLabelText('채팅방 연결 중')).toBeInTheDocument();
  });

  it('keeps messages in place while showing a message loading error overlay', () => {
    mocks.chatRoom = {
      ...mocks.chatRoom,
      room: { _id: 'room-1', name: '테스트방', participants: [] },
      loading: false,
      messageLoadError: new Error('메시지 로드 실패'),
    };

    render(<ChatRoomView roomId="room-1" onNavigate={vi.fn()} onReplace={vi.fn()} asPath="/chat/room-1" />);

    expect(screen.getByText('chat messages')).toBeInTheDocument();
    expect(screen.getByTestId('message-load-error-overlay')).toHaveStyle({
      position: 'absolute',
    });
    expect(screen.getByRole('button', { name: '메시지 다시 로드' })).toBeInTheDocument();
  });
});
