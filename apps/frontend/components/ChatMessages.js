import React, { useCallback } from 'react';
import { Spinner, Text, VStack } from '@vapor-ui/core';
import SystemMessage from './SystemMessage';
import FileMessage from './FileMessage';
import UserMessage from './UserMessage';
import { useInfiniteScroll } from '../hooks/useInfiniteScroll';
import { useAutoScroll } from '../hooks/useAutoScroll';

const LoadingIndicator = React.memo(() => (
  <div className="loading-messages">
    <Spinner size="md" colorPalette="primary" aria-label="이전 메시지 로딩 중" />
    <span className="text-secondary text-sm">이전 메시지를 불러오는 중...</span>
  </div>
));
LoadingIndicator.displayName = 'LoadingIndicator';

const MessageHistoryEnd = React.memo(() => (
  <div className="text-center p-2 mb-4" data-testid="message-history-end">
    <Text typography="body2" foreground="hint-100">더 이상 불러올 메시지가 없습니다.</Text>
  </div>
));
MessageHistoryEnd.displayName = 'MessageHistoryEnd';

const EmptyMessages = React.memo(() => (
  <div className="empty-messages">
    <Text typography="body1">아직 메시지가 없습니다.</Text>
    <Text typography="body2" foreground="hint-100">첫 메시지를 보내보세요!</Text>
  </div>
));
EmptyMessages.displayName = 'EmptyMessages';

const ChatMessages = ({
  messages = [],
  currentUser = null,
  room = null,
  loadingMessages = false,
  hasMoreMessages = true,
  onReactionAdd = () => {},
  onReactionRemove = () => {},
  onLoadMore = () => {}
}) => {
  // 무한 스크롤 훅
  const { sentinelRef } = useInfiniteScroll(
    onLoadMore,
    hasMoreMessages,
    loadingMessages
  );

  // 자동 스크롤 훅 (스크롤 복원 기능 포함)
  const { containerRef, scrollToBottom, isNearBottom } = useAutoScroll(
    messages,
    currentUser?.id,
    loadingMessages,
    100 // 하단 100px 이내면 자동 스크롤
  );
  const currentUserId = currentUser?.id;
  const isMine = useCallback((msg) => {
    if (!msg?.sender || !currentUserId) return false;
    
    return (
      msg.sender._id === currentUserId ||
      msg.sender.id === currentUserId ||
      msg.sender === currentUserId
    );
  }, [currentUserId]);

  const renderMessage = useCallback((msg) => {
    if (!msg) return null;

    const commonProps = {
      currentUser,
      room,
      onReactionAdd,
      onReactionRemove
    };

    const MessageComponent = {
      system: SystemMessage,
      file: FileMessage
    }[msg.type] || UserMessage;

    return (
      <div
        key={msg._id}
        className="chat-message-item"
        data-testid="chat-message-item"
        data-message-id={msg._id}
      >
      <MessageComponent
        {...commonProps}
        msg={msg}
        content={msg.content}
        isMine={msg.type !== 'system' ? isMine(msg) : undefined}
        isStreaming={msg.type === 'ai' ? (msg.isStreaming || false) : undefined}
      />
      </div>
    );
  }, [currentUser, room, isMine, onReactionAdd, onReactionRemove]);

  return (
    <VStack
      ref={containerRef}
      className="h-full overflow-y-auto overflow-x-hidden [overflow-scrolling:touch]"
      style={{ scrollbarGutter: 'stable' }}
      $css={{
        gap: '$200',
        padding: '$300',
      }}
      role="log"
      aria-live="polite"
      aria-atomic="false"
      data-testid="chat-messages-container"
    >
      {/* Sentinel 요소 - 스크롤 맨 위에 배치하여 위로 스크롤 시 이전 메시지 로드 */}
      {hasMoreMessages && (
        <div
          ref={sentinelRef}
          style={{
            height: '20px',
            margin: '10px 0',
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center'
          }}
        >
          {loadingMessages && <LoadingIndicator />}
        </div>
      )}

      {!hasMoreMessages && messages.length > 0 && (
        <MessageHistoryEnd />
      )}

      {messages.length === 0 ? (
        <EmptyMessages />
      ) : (
        messages.map(renderMessage)
      )}
    </VStack>
  );
};

ChatMessages.displayName = 'ChatMessages';

export default React.memo(ChatMessages);
