import { describe, expect, it, vi } from 'vitest';
import { createSocketClient } from '../socketClient';

const createEventEmitter = () => {
  const handlers = new Map();
  const addHandler = (event, handler) => {
    const eventHandlers = handlers.get(event) || [];
    eventHandlers.push(handler);
    handlers.set(event, eventHandlers);
  };
  const removeHandler = (event, handler) => {
    handlers.set(
      event,
      (handlers.get(event) || []).filter(registered => registered !== handler),
    );
  };

  return {
    on: vi.fn(addHandler),
    once: vi.fn(addHandler),
    off: vi.fn(removeHandler),
    emitToClient(event, payload) {
      for (const handler of handlers.get(event) || []) {
        handler(payload);
      }
    },
    listenerCount(event) {
      return (handlers.get(event) || []).length;
    },
  };
};

const createEventSocket = ({ connected = true, id = 'socket-1' } = {}) => ({
  id,
  connected,
  emit: vi.fn(),
  ...createEventEmitter(),
  // socket.io v4 의 재연결 이벤트는 manager 에서 발생한다.
  io: createEventEmitter(),
});

describe('socketClient', () => {
  it('delegates lifecycle methods to the socket service', async () => {
    const socket = { id: 'socket-1' };
    const service = {
      connect: vi.fn().mockResolvedValue(socket),
      disconnect: vi.fn(),
      isConnected: vi.fn(() => true),
    };
    const client = createSocketClient(service);

    await expect(client.connect({ auth: { token: 'token-1' } })).resolves.toBe(socket);
    expect(client.isConnected()).toBe(true);
    expect(client.canSend()).toBe(true);

    client.disconnect();

    expect(service.disconnect).toHaveBeenCalledTimes(1);
  });

  it('delegates fire-and-forget send to the socket service', () => {
    const service = {
      send: vi.fn(),
    };
    const client = createSocketClient(service);

    client.send('messageReaction', { messageId: 'message-1' });

    expect(service.send).toHaveBeenCalledWith('messageReaction', { messageId: 'message-1' });
  });

  it('throws through the client when fire-and-forget send is disconnected', () => {
    const error = new Error('Socket is not connected');
    const service = {
      send: vi.fn(() => {
        throw error;
      }),
    };
    const client = createSocketClient(service);

    expect(() => client.send('messageReaction', { messageId: 'message-1' })).toThrow(error);
    expect(service.send).toHaveBeenCalledWith('messageReaction', { messageId: 'message-1' });
  });

  it('sends message reactions with the server contract', () => {
    const service = {
      send: vi.fn(),
    };
    const client = createSocketClient(service);

    client.sendMessageReaction('message-1', '👍', 'remove');

    expect(service.send).toHaveBeenCalledWith('messageReaction', {
      messageId: 'message-1',
      reaction: '👍',
      type: 'remove',
    });
  });

  it('sends chat room domain events through the socket service', () => {
    const service = {
      send: vi.fn(),
    };
    const client = createSocketClient(service);

    client.sendChatMessage({ room: 'room-1', type: 'text', content: 'hello' });
    client.fetchPreviousMessages({ roomId: 'room-1', limit: 30 });
    client.joinRoom('room-1');
    client.leaveRoom('room-1');
    client.markMessagesAsRead(['message-1']);

    expect(service.send).toHaveBeenCalledWith('chatMessage', {
      room: 'room-1',
      type: 'text',
      content: 'hello',
    });
    expect(service.send).toHaveBeenCalledWith('fetchPreviousMessages', {
      roomId: 'room-1',
      limit: 30,
    });
    expect(service.send).toHaveBeenCalledWith('joinRoom', 'room-1');
    expect(service.send).toHaveBeenCalledWith('leaveRoom', 'room-1');
    expect(service.send).toHaveBeenCalledWith('markMessagesAsRead', {
      messageIds: ['message-1'],
    });
  });

  it('sends joinRoom through a target socket when provided', () => {
    const socket = { id: 'socket-1' };
    const service = {
      send: vi.fn(),
      sendOn: vi.fn(),
    };
    const client = createSocketClient(service);

    client.joinRoom('room-1', socket);

    expect(service.sendOn).toHaveBeenCalledWith(socket, 'joinRoom', 'room-1');
    expect(service.send).not.toHaveBeenCalled();
  });

  it('marks messages as read through a target socket when provided', () => {
    const socket = { id: 'socket-1' };
    const service = {
      send: vi.fn(),
      sendOn: vi.fn(),
    };
    const client = createSocketClient(service);

    client.markMessagesAsRead(['message-1'], socket);

    expect(service.sendOn).toHaveBeenCalledWith(socket, 'markMessagesAsRead', {
      messageIds: ['message-1'],
    });
    expect(service.send).not.toHaveBeenCalled();
  });

  it('tries to leave a room through a target socket without throwing', () => {
    const socket = { id: 'socket-1' };
    const service = {
      trySendOn: vi.fn(() => true),
    };
    const client = createSocketClient(service);

    expect(client.tryLeaveRoom('room-1', socket)).toBe(true);
    expect(service.trySendOn).toHaveBeenCalledWith(socket, 'leaveRoom', 'room-1');
  });

  it('waits for joinRoomSuccess and cleans join listeners', async () => {
    vi.useFakeTimers();
    const socket = createEventSocket();
    const service = {
      sendOn: vi.fn(),
    };
    const client = createSocketClient(service);

    const join = client.joinRoomAndWait('room-1', socket, { timeoutMs: 1000 });
    socket.emitToClient('joinRoomSuccess', { roomId: 'room-1' });

    await expect(join).resolves.toEqual({ roomId: 'room-1' });
    expect(service.sendOn).toHaveBeenCalledWith(socket, 'joinRoom', 'room-1');
    expect(socket.listenerCount('joinRoomSuccess')).toBe(0);
    expect(socket.listenerCount('joinRoomError')).toBe(0);
    expect(socket.listenerCount('error')).toBe(0);
    vi.useRealTimers();
  });

  it('rejects joinRoomAndWait on joinRoomError and cleans listeners', async () => {
    vi.useFakeTimers();
    const socket = createEventSocket();
    const client = createSocketClient({ sendOn: vi.fn() });
    const error = new Error('join failed');

    const join = client.joinRoomAndWait('room-1', socket, { timeoutMs: 1000 });
    socket.emitToClient('joinRoomError', error);

    await expect(join).rejects.toBe(error);
    expect(socket.listenerCount('joinRoomSuccess')).toBe(0);
    expect(socket.listenerCount('joinRoomError')).toBe(0);
    expect(socket.listenerCount('error')).toBe(0);
    vi.useRealTimers();
  });

  it('rejects joinRoomAndWait on timeout and cleans listeners', async () => {
    vi.useFakeTimers();
    const socket = createEventSocket();
    const client = createSocketClient({ sendOn: vi.fn() });

    const join = client.joinRoomAndWait('room-1', socket, { timeoutMs: 1000 });
    const expectation = expect(join).rejects.toThrow('채팅방 입장 시간이 초과되었습니다.');
    await vi.advanceTimersByTimeAsync(1000);

    await expectation;
    expect(socket.listenerCount('joinRoomSuccess')).toBe(0);
    expect(socket.listenerCount('joinRoomError')).toBe(0);
    expect(socket.listenerCount('error')).toBe(0);
    vi.useRealTimers();
  });

  it('waits for the message ack and cleans send listeners', async () => {
    vi.useFakeTimers();
    const socket = createEventSocket();
    const service = {
      sendOn: vi.fn(),
    };
    const client = createSocketClient(service);
    const payload = {
      room: 'room-1',
      type: 'text',
      content: 'hello',
      clientMessageId: '18e12ec4-1003-4d22-8c6c-12e626a1f84d',
    };

    const send = client.sendChatMessageAndWait(payload, socket, { timeoutMs: 1000 });
    socket.emitToClient('message', {
      id: 'message-1',
      clientMessageId: payload.clientMessageId,
    });

    await expect(send).resolves.toEqual({
      id: 'message-1',
      clientMessageId: payload.clientMessageId,
    });
    expect(service.sendOn).toHaveBeenCalledWith(socket, 'chatMessage', payload);
    expect(socket.listenerCount('message')).toBe(0);
    expect(socket.listenerCount('error')).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
    vi.useRealTimers();
  });

  it('ignores message events with a different clientMessageId', async () => {
    vi.useFakeTimers();
    const socket = createEventSocket();
    const client = createSocketClient({ sendOn: vi.fn() });
    const clientMessageId = 'c780516a-c2e1-42af-8b43-bbb91c772e91';
    let settled = false;

    const send = client.sendChatMessageAndWait(
      { room: 'room-1', type: 'text', content: 'hello', clientMessageId },
      socket,
      { timeoutMs: 1000 },
    );
    send.finally(() => {
      settled = true;
    });

    socket.emitToClient('message', {
      _id: 'unrelated-message',
      clientMessageId: '3892338b-e39d-48ed-bbab-e6e9af31c682',
    });
    await Promise.resolve();

    expect(settled).toBe(false);
    expect(socket.listenerCount('message')).toBe(1);

    const response = { _id: 'message-1', clientMessageId };
    socket.emitToClient('message', response);

    await expect(send).resolves.toBe(response);
    expect(socket.listenerCount('message')).toBe(0);
    expect(socket.listenerCount('error')).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
    vi.useRealTimers();
  });

  it('rejects sendChatMessageAndWait on a server error', async () => {
    vi.useFakeTimers();
    const socket = createEventSocket();
    const client = createSocketClient({ sendOn: vi.fn() });
    const error = new Error('rejected');

    const send = client.sendChatMessageAndWait(
      {
        room: 'room-1',
        type: 'text',
        content: 'hello',
        clientMessageId: 'f0d844d9-d84e-49b3-8e82-1fe09306f558',
      },
      socket,
      { timeoutMs: 1000 },
    );
    socket.emitToClient('error', error);

    await expect(send).rejects.toBe(error);
    expect(socket.listenerCount('message')).toBe(0);
    expect(socket.listenerCount('error')).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
    vi.useRealTimers();
  });

  it('rejects sendChatMessageAndWait on timeout and cleans listeners', async () => {
    vi.useFakeTimers();
    const socket = createEventSocket();
    const client = createSocketClient({ sendOn: vi.fn() });

    const send = client.sendChatMessageAndWait(
      {
        room: 'room-1',
        type: 'text',
        content: 'hello',
        clientMessageId: '69e14aa5-d114-4285-8909-e747f24eb4ea',
      },
      socket,
      { timeoutMs: 1000, maxRetries: 0 },
    );
    const expectation = expect(send).rejects.toThrow('메시지 전송이 지연되고 있습니다. 다시 시도해주세요.');
    await vi.advanceTimersByTimeAsync(1000);

    await expectation;
    expect(socket.listenerCount('message')).toBe(0);
    expect(socket.listenerCount('error')).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
    vi.useRealTimers();
  });

  it('retries a timed out message with the same clientMessageId and payload', async () => {
    vi.useFakeTimers();
    const socket = createEventSocket();
    const service = { sendOn: vi.fn() };
    const client = createSocketClient(service);
    const payload = {
      room: 'room-1',
      type: 'text',
      content: 'retry me',
      clientMessageId: '67e5f2fe-d16f-4e7e-ae8e-264e5ad45376',
    };

    const send = client.sendChatMessageAndWait(payload, socket, {
      timeoutMs: 100,
      maxRetries: 1,
      retryDelayMs: 20,
    });

    await vi.advanceTimersByTimeAsync(120);
    expect(service.sendOn).toHaveBeenCalledTimes(2);
    expect(service.sendOn.mock.calls.map(([, , emittedPayload]) => emittedPayload)).toEqual([
      payload,
      payload,
    ]);

    const response = { _id: 'message-1', clientMessageId: payload.clientMessageId };
    socket.emitToClient('message', response);
    await expect(send).resolves.toBe(response);
    expect(socket.listenerCount('message')).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
    vi.useRealTimers();
  });

  it('rejects after the configured retry count is exhausted', async () => {
    vi.useFakeTimers();
    const socket = createEventSocket();
    const service = { sendOn: vi.fn() };
    const client = createSocketClient(service);
    const send = client.sendChatMessageAndWait(
      {
        room: 'room-1',
        type: 'text',
        content: 'never acked',
        clientMessageId: '53f79fdc-81cf-43cf-bd39-888433e36988',
      },
      socket,
      { timeoutMs: 100, maxRetries: 1, retryDelayMs: 20 },
    );
    const expectation = expect(send).rejects.toMatchObject({ code: 'SOCKET_TIMEOUT' });

    await vi.advanceTimersByTimeAsync(220);

    await expectation;
    expect(service.sendOn).toHaveBeenCalledTimes(2);
    expect(socket.listenerCount('message')).toBe(0);
    expect(socket.listenerCount('error')).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
    vi.useRealTimers();
  });

  it('cleans message listeners and timers when the request is aborted', async () => {
    vi.useFakeTimers();
    const socket = createEventSocket();
    const client = createSocketClient({ sendOn: vi.fn() });
    const controller = new AbortController();
    const send = client.sendChatMessageAndWait(
      {
        room: 'room-1',
        type: 'text',
        content: 'cancel me',
        clientMessageId: '71985cea-bd7d-42ee-85c6-5e6e5b9dfcfe',
      },
      socket,
      { timeoutMs: 1000, signal: controller.signal },
    );

    controller.abort();

    await expect(send).rejects.toMatchObject({ name: 'AbortError' });
    expect(socket.listenerCount('message')).toBe(0);
    expect(socket.listenerCount('error')).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
    vi.useRealTimers();
  });

  it('rejects non-array message ids before marking messages as read', () => {
    const service = {
      send: vi.fn(),
    };
    const client = createSocketClient(service);

    expect(() => client.markMessagesAsRead({ messageIds: ['message-1'] })).toThrowError(
      'messageIds must be an array',
    );
    expect(service.send).not.toHaveBeenCalled();
  });

  it('waits for previousMessagesLoaded and sends fetchPreviousMessages', async () => {
    vi.useFakeTimers();
    const socket = createEventSocket();
    const service = {
      sendOn: vi.fn(),
    };
    const client = createSocketClient(service);
    const payload = { roomId: 'room-1', limit: 30 };
    const response = { messages: [{ _id: 'message-1' }], hasMore: false };

    const fetch = client.fetchPreviousMessagesAndWait(payload, socket, { timeoutMs: 1000 });
    socket.emitToClient('previousMessagesLoaded', response);

    await expect(fetch).resolves.toBe(response);
    expect(service.sendOn).toHaveBeenCalledWith(socket, 'fetchPreviousMessages', payload);
    expect(socket.listenerCount('previousMessagesLoaded')).toBe(0);
    expect(socket.listenerCount('error')).toBe(0);
    vi.useRealTimers();
  });

  it('rejects fetchPreviousMessagesAndWait on socket error', async () => {
    vi.useFakeTimers();
    const socket = createEventSocket();
    const client = createSocketClient({ sendOn: vi.fn() });
    const error = new Error('fetch failed');

    const fetch = client.fetchPreviousMessagesAndWait(
      { roomId: 'room-1', limit: 30 },
      socket,
      { timeoutMs: 1000 },
    );
    socket.emitToClient('error', error);

    await expect(fetch).rejects.toBe(error);
    expect(socket.listenerCount('previousMessagesLoaded')).toBe(0);
    expect(socket.listenerCount('error')).toBe(0);
    vi.useRealTimers();
  });

  it('subscribes room event handlers and returns an unsubscribe function', () => {
    const socket = createEventSocket();
    const client = createSocketClient({ send: vi.fn() });
    const handlers = {
      onParticipantsUpdate: vi.fn(),
      onMessagesRead: vi.fn(),
      onMessage: vi.fn(),
      onPreviousMessagesLoaded: vi.fn(),
      onMessageReactionUpdate: vi.fn(),
      onSessionEnded: vi.fn(),
      onError: vi.fn(),
    };

    const unsubscribe = client.subscribeRoomEvents(socket, handlers);

    socket.emitToClient('participantsUpdate', ['user-1']);
    socket.emitToClient('messagesRead', { userId: 'user-1', messageIds: ['message-1'] });
    socket.emitToClient('message', { _id: 'message-1' });
    socket.emitToClient('previousMessagesLoaded', { messages: [], hasMore: false });
    socket.emitToClient('messageReactionUpdate', { messageId: 'message-1' });
    socket.emitToClient('session_ended');
    socket.emitToClient('error', { code: 'MESSAGE_REJECTED' });

    expect(handlers.onParticipantsUpdate).toHaveBeenCalledWith(['user-1']);
    expect(handlers.onMessagesRead).toHaveBeenCalledWith({ userId: 'user-1', messageIds: ['message-1'] });
    expect(handlers.onMessage).toHaveBeenCalledWith({ _id: 'message-1' });
    expect(handlers.onPreviousMessagesLoaded).toHaveBeenCalledWith({ messages: [], hasMore: false });
    expect(handlers.onMessageReactionUpdate).toHaveBeenCalledWith({ messageId: 'message-1' });
    expect(handlers.onSessionEnded).toHaveBeenCalledTimes(1);
    expect(handlers.onError).toHaveBeenCalledWith({ code: 'MESSAGE_REJECTED' });

    unsubscribe();

    expect(socket.listenerCount('participantsUpdate')).toBe(0);
    expect(socket.listenerCount('messagesRead')).toBe(0);
    expect(socket.listenerCount('message')).toBe(0);
    expect(socket.listenerCount('previousMessagesLoaded')).toBe(0);
    expect(socket.listenerCount('messageReactionUpdate')).toBe(0);
    expect(socket.listenerCount('session_ended')).toBe(0);
    expect(socket.listenerCount('error')).toBe(0);
  });

  it('subscribes connection event handlers and returns an unsubscribe function', () => {
    const socket = createEventSocket();
    const client = createSocketClient({ send: vi.fn() });
    const handlers = {
      onConnect: vi.fn(),
      onDisconnect: vi.fn(),
      onConnectError: vi.fn(),
      onReconnecting: vi.fn(),
      onReconnect: vi.fn(),
    };

    const unsubscribe = client.subscribeConnectionEvents(socket, handlers);

    socket.emitToClient('connect');
    socket.emitToClient('disconnect', 'transport close');
    socket.emitToClient('connect_error', new Error('connect failed'));
    // 재연결 이벤트는 manager 에서만 발생한다.
    socket.io.emitToClient('reconnect_attempt', 2);
    socket.io.emitToClient('reconnect', 3);

    expect(handlers.onConnect).toHaveBeenCalledTimes(1);
    expect(handlers.onDisconnect).toHaveBeenCalledWith('transport close');
    expect(handlers.onConnectError).toHaveBeenCalledWith(new Error('connect failed'));
    expect(handlers.onReconnecting).toHaveBeenCalledWith(2);
    expect(handlers.onReconnect).toHaveBeenCalledWith(3);

    unsubscribe();

    expect(socket.listenerCount('connect')).toBe(0);
    expect(socket.listenerCount('disconnect')).toBe(0);
    expect(socket.listenerCount('connect_error')).toBe(0);
    expect(socket.io.listenerCount('reconnect_attempt')).toBe(0);
    expect(socket.io.listenerCount('reconnect')).toBe(0);
  });

  it('routes reconnect_failed through the manager', () => {
    const socket = createEventSocket();
    const client = createSocketClient({ send: vi.fn() });
    const handlers = { onReconnectFailed: vi.fn() };

    const unsubscribe = client.subscribeConnectionEvents(socket, handlers);

    socket.io.emitToClient('reconnect_failed');
    expect(handlers.onReconnectFailed).toHaveBeenCalledTimes(1);

    unsubscribe();
    expect(socket.io.listenerCount('reconnect_failed')).toBe(0);
  });
});
