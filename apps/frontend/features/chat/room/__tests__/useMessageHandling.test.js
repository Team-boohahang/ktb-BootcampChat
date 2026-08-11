import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Toast } from '@/components/Toast';
import socketClient from '@/lib/socket/socketClient';
import fileService from '@/services/fileService';
import { useMessageHandling } from '../useMessageHandling';

vi.mock('@/components/Toast', () => ({
  Toast: {
    error: vi.fn(),
  },
  default: () => null,
}));

vi.mock('@/services/fileService', () => ({
  default: {
    uploadFile: vi.fn(),
  },
}));

vi.mock('@/lib/socket/socketClient', () => ({
  default: {
    canSend: vi.fn(() => true),
    sendChatMessageAndWait: vi.fn(),
    fetchPreviousMessages: vi.fn(),
  },
}));

const roomId = 'room-1';

const currentUser = {
  token: 'token-1',
  sessionId: 'session-1',
};

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

describe('useMessageHandling', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    socketClient.canSend.mockReturnValue(true);
  });

  it('sends trimmed text message through the subscribed room socket', async () => {
    const roomSocket = { connected: true };
    const socketRef = { current: roomSocket };
    const { result } = renderHook(() =>
      useMessageHandling(currentUser, roomId, vi.fn(), [], false, vi.fn(), socketRef)
    );

    await act(async () => {
      await result.current.handleMessageSubmit({ content: '  hello  ' });
    });

    expect(socketClient.sendChatMessageAndWait).toHaveBeenCalledWith(
      expect.objectContaining({
        room: 'room-1',
        type: 'text',
        content: 'hello',
        clientMessageId: expect.stringMatching(UUID_PATTERN),
      }),
      roomSocket,
      expect.objectContaining({ signal: expect.anything() }),
    );
  });

  it('shows a connection error without emitting when disconnected', async () => {
    socketClient.canSend.mockReturnValue(false);
    const { result } = renderHook(() =>
      useMessageHandling(currentUser, roomId, vi.fn())
    );

    await act(async () => {
      await result.current.handleMessageSubmit({ content: 'hello' });
    });

    expect(socketClient.sendChatMessageAndWait).not.toHaveBeenCalled();
    expect(Toast.error).toHaveBeenCalledWith('채팅 서버와 연결이 끊어졌습니다.');
  });

  it('uploads files, sends file messages, and clears file preview state', async () => {
    const roomSocket = { connected: true };
    const socketRef = { current: roomSocket };
    fileService.uploadFile.mockResolvedValue({
      success: true,
      data: {
        file: {
          _id: 'file-1',
          filename: 'stored.pdf',
          originalname: 'sample.pdf',
          mimetype: 'application/pdf',
          size: 128,
        },
      },
    });
    const { result } = renderHook(() =>
      useMessageHandling(currentUser, roomId, vi.fn(), [], false, vi.fn(), socketRef)
    );

    await act(async () => {
      result.current.setFilePreview({ name: 'sample.pdf' });
      await result.current.handleMessageSubmit({
        type: 'file',
        content: 'attached',
        fileData: {
          file: { name: 'sample.pdf' },
        },
      });
    });

    expect(socketClient.sendChatMessageAndWait).toHaveBeenCalledWith(
      expect.objectContaining({
        room: 'room-1',
        type: 'file',
        content: 'attached',
        clientMessageId: expect.stringMatching(UUID_PATTERN),
        fileData: {
          _id: 'file-1',
          filename: 'stored.pdf',
          originalname: 'sample.pdf',
          mimetype: 'application/pdf',
          size: 128,
        },
      }),
      roomSocket,
      expect.objectContaining({ signal: expect.anything() }),
    );
    expect(result.current.filePreview).toBeNull();
    expect(result.current.uploadError).toBeNull();
  });

  it('guards synchronously against two submissions while the first is pending', async () => {
    const roomSocket = { connected: true };
    const socketRef = { current: roomSocket };
    let resolveSend;
    socketClient.sendChatMessageAndWait.mockReturnValue(new Promise(resolve => {
      resolveSend = resolve;
    }));
    const { result } = renderHook(() =>
      useMessageHandling(currentUser, roomId, vi.fn(), [], false, vi.fn(), socketRef)
    );

    let firstSubmission;
    let secondSubmission;
    act(() => {
      firstSubmission = result.current.handleMessageSubmit({ type: 'text', content: 'hello' });
      secondSubmission = result.current.handleMessageSubmit({ type: 'text', content: 'hello' });
    });

    expect(socketClient.sendChatMessageAndWait).toHaveBeenCalledTimes(1);
    await expect(secondSubmission).resolves.toBe(false);
    expect(result.current.sending).toBe(true);

    await act(async () => {
      resolveSend({ _id: 'message-1' });
      await firstSubmission;
    });

    expect(result.current.sending).toBe(false);
  });

  it('releases the sending guard after failure so a later message can be sent', async () => {
    const roomSocket = { connected: true };
    const socketRef = { current: roomSocket };
    socketClient.sendChatMessageAndWait
      .mockRejectedValueOnce(new Error('send failed'))
      .mockResolvedValueOnce({ _id: 'message-2' });
    const { result } = renderHook(() =>
      useMessageHandling(currentUser, roomId, vi.fn(), [], false, vi.fn(), socketRef)
    );

    await act(async () => {
      await result.current.handleMessageSubmit({ type: 'text', content: 'first' });
    });
    await act(async () => {
      await result.current.handleMessageSubmit({ type: 'text', content: 'second' });
    });

    expect(socketClient.sendChatMessageAndWait).toHaveBeenCalledTimes(2);
    const firstId = socketClient.sendChatMessageAndWait.mock.calls[0][0].clientMessageId;
    const secondId = socketClient.sendChatMessageAndWait.mock.calls[1][0].clientMessageId;
    expect(firstId).toMatch(UUID_PATTERN);
    expect(secondId).toMatch(UUID_PATTERN);
    expect(secondId).not.toBe(firstId);
    expect(result.current.sending).toBe(false);
  });
});
