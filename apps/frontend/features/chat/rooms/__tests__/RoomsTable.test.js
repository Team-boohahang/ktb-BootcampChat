import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import RoomsTable from '../RoomsTable';
import { CONNECTION_STATUS } from '../useServerConnection';

describe('RoomsTable', () => {
  it('긴 채팅방 이름을 한 줄 말줄임으로 제한한다', () => {
    const roomName = 'CLS baseline 1%7!8!6#4*5!1*6!4_9-9!4%2*0-.@8-7-8&5-4%8@6^6@1*7@8%3-9@8^';

    render(
      <RoomsTable
        rooms={[{
          _id: 'room-1',
          name: roomName,
          participants: [],
          recentMessageCount: 0,
          createdAt: '2026-08-12T00:00:00.000Z',
        }]}
        connectionStatus={CONNECTION_STATUS.CONNECTED}
        onJoinRoom={vi.fn()}
      />
    );

    const roomNameElement = screen.getByText(roomName);

    expect(roomNameElement).toHaveAttribute('title', roomName);
    expect(roomNameElement).toHaveStyle({
      overflow: 'hidden',
      textOverflow: 'ellipsis',
      whiteSpace: 'nowrap',
    });
    expect(roomNameElement.closest('table')).toHaveStyle({
      tableLayout: 'fixed',
    });
  });
});
