import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ChatMessages from '../ChatMessages';

vi.mock('../../hooks/useInfiniteScroll', () => ({
  useInfiniteScroll: () => ({ sentinelRef: { current: null } }),
}));

vi.mock('../../hooks/useAutoScroll', () => ({
  useAutoScroll: () => ({
    containerRef: { current: null },
    scrollToBottom: vi.fn(),
    isNearBottom: true,
  }),
}));

vi.mock('../SystemMessage', () => ({
  default: ({ msg }) => React.createElement('div', { 'data-testid': 'message' }, msg.content),
}));

vi.mock('../FileMessage', () => ({
  default: ({ msg }) => React.createElement('div', { 'data-testid': 'message' }, msg.content),
}));

vi.mock('../UserMessage', () => ({
  default: ({ msg }) => React.createElement('div', { 'data-testid': 'message' }, msg.content),
}));

describe('ChatMessages', () => {
  it('renders messages in the order provided by the message state', () => {
    const messages = [
      {
        _id: 'early',
        content: 'early message',
        timestamp: '2026-06-20T11:00:00.000Z',
        sender: { _id: 'other' },
      },
      {
        _id: 'late',
        content: 'late message',
        timestamp: '2026-06-20T12:00:00.000Z',
        sender: { _id: 'other' },
      },
    ];

    render(
      React.createElement(ChatMessages, {
        messages,
        currentUser: { id: 'me' },
        hasMoreMessages: false,
      })
    );

    expect(screen.getAllByTestId('message').map((node) => node.textContent)).toEqual([
      'early message',
      'late message',
    ]);
  });

  it('renders message wrappers without an inaccurate intrinsic height', () => {
    render(
      React.createElement(ChatMessages, {
        messages: [
          {
            _id: 'message-1',
            content: 'discoverable message',
            timestamp: '2026-06-20T11:00:00.000Z',
            sender: { _id: 'other' },
          },
        ],
        currentUser: { id: 'me' },
        hasMoreMessages: true,
        loadingMessages: true,
      })
    );

    const message = screen.getByText('discoverable message');
    const messageWrapper = message.closest('[data-testid="chat-message-item"]');

    expect(message).toBeInTheDocument();
    expect(messageWrapper).toHaveAttribute('data-message-id', 'message-1');
    expect(messageWrapper.style.contentVisibility).toBe('');
    expect(messageWrapper.style.containIntrinsicSize).toBe('');
    expect(screen.getByText('이전 메시지를 불러오는 중...')).toBeInTheDocument();
  });
});
