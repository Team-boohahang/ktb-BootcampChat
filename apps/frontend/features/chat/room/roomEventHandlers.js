import {
  deriveUniqueSortedMessages,
  mergeIncomingMessages,
  mergeSortedMessages,
} from '../messages/useMessageList';

export const processLoadedRoomMessages = ({
  loadedMessages,
  hasMore,
  isInitialLoad = false,
  processedMessageIds,
  setMessages,
  setHasMoreMessages,
  initialLoadCompletedRef,
}) => {
  if (!Array.isArray(loadedMessages)) {
    throw new Error('Invalid messages format');
  }

  const result = deriveUniqueSortedMessages(
    [],
    loadedMessages,
    processedMessageIds.current
  );
  processedMessageIds.current = result.processedMessageIds;

  let nextMessages;
  if (result.messages.length > 0) {
    setMessages(prev => {
      nextMessages = mergeSortedMessages(prev, result.messages);
      return nextMessages;
    });
  }
  setHasMoreMessages(hasMore);

  if (isInitialLoad) {
    initialLoadCompletedRef.current = true;
  }

  return nextMessages;
};

export const applyReadReceipts = (messages, { userId, messageIds, timestamp }) => {
  const messageIdSet = new Set(messageIds);
  let hasChanges = false;
  const nextMessages = messages.map(msg => {
    if (!messageIdSet.has(msg._id)) {
      return msg;
    }

    const alreadyRead = msg.readers?.some(reader =>
      reader.userId === userId || reader._id === userId
    );
    if (alreadyRead) {
      return msg;
    }

    hasChanges = true;
    return {
      ...msg,
      readers: [...(msg.readers || []), { userId, readAt: timestamp || new Date() }],
    };
  });

  return hasChanges ? nextMessages : messages;
};

export const createRoomEventHandlers = ({
  mountedRef,
  messageProcessingRef,
  processedMessageIds,
  initialLoadCompletedRef,
  processMessages,
  setRoom,
  setMessages,
  setLoadingMessages,
  setError,
  setHasMoreMessages,
  cleanup,
  logout,
  onReplace,
  handleReactionUpdate,
  showRejectedMessage,
}) => {
  let pendingMessages = [];
  let scheduledFlush = null;

  const flushPendingMessages = () => {
    scheduledFlush = null;
    if (!mountedRef.current || pendingMessages.length === 0) return;

    const messagesToAppend = pendingMessages;
    pendingMessages = [];
    setMessages(prev => mergeIncomingMessages(prev, messagesToAppend));
  };

  const scheduleMessageFlush = () => {
    if (scheduledFlush) return;

    if (typeof globalThis.requestAnimationFrame === 'function') {
      scheduledFlush = {
        type: 'frame',
        id: globalThis.requestAnimationFrame(flushPendingMessages),
      };
      return;
    }

    scheduledFlush = {
      type: 'timeout',
      id: setTimeout(flushPendingMessages, 0),
    };
  };

  const dispose = () => {
    if (scheduledFlush?.type === 'frame') {
      globalThis.cancelAnimationFrame?.(scheduledFlush.id);
    } else if (scheduledFlush?.type === 'timeout') {
      clearTimeout(scheduledFlush.id);
    }

    pendingMessages.forEach(message => {
      processedMessageIds.current.delete(message._id);
    });
    pendingMessages = [];
    scheduledFlush = null;
  };

  const handlePreviousMessages = (response) => {
    if (!mountedRef.current || messageProcessingRef.current) return;
    try {
      messageProcessingRef.current = true;
      if (!response || typeof response !== 'object') {
        throw new Error('Invalid response format');
      }
      const { messages: loadedMessages = [], hasMore } = response;
      const isInitialLoad = !initialLoadCompletedRef.current;
      processMessages(loadedMessages, hasMore, isInitialLoad);
      setLoadingMessages(false);
    } catch (error) {
      setLoadingMessages(false);
      setError('메시지 처리 중 오류가 발생했습니다.');
      setHasMoreMessages(false);
    } finally {
      messageProcessingRef.current = false;
    }
  };

  return {
    dispose,
    onParticipantsUpdate: (participants) => {
      if (!mountedRef.current) return;
      setRoom(prev => ({ ...prev, participants: participants || [] }));
    },
    onMessagesRead: (payload) => {
      if (!mountedRef.current) return;
      setMessages(prev => applyReadReceipts(prev, payload));
    },
    onMessage: (incoming) => {
      if (!mountedRef.current || messageProcessingRef.current) return;
      if (!incoming?._id || processedMessageIds.current.has(incoming._id)) return;
      processedMessageIds.current.add(incoming._id);
      pendingMessages.push(incoming);
      scheduleMessageFlush();
    },
    onPreviousMessagesLoaded: handlePreviousMessages,
    onMessageReactionUpdate: (data) => {
      if (!mountedRef.current) return;
      handleReactionUpdate(data);
    },
    onSessionEnded: () => {
      if (!mountedRef.current) return;
      cleanup();
      logout();
      onReplace('/?error=session_expired');
    },
    onError: (error) => {
      if (!mountedRef.current) return;
      console.error('Socket error:', error);
      if (error?.code === 'MESSAGE_REJECTED') {
        showRejectedMessage(error.message || '금칙어가 포함되어 메시지를 전송할 수 없습니다.');
        return;
      }
      setError(error.message || '채팅 연결에 문제가 발생했습니다.');
    },
  };
};
