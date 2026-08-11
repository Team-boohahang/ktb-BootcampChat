import React from 'react';
import {
  VStack,
  Button,
  Text,
  Callout,
  Spinner,
  Flex,
} from '@vapor-ui/core';
import { ErrorCircleOutlineIcon } from '@vapor-ui/icons';
import { useChatRoom } from './useChatRoom';
import ChatMessages from '@/components/ChatMessages';
import ChatInput from '@/components/ChatInput';
import ChatRoomInfo from '@/components/ChatRoomInfo';
import ConnectionErrorBanner from '@/components/ConnectionErrorBanner';

const CHAT_ROOM_SHELL_STYLE = {
  height: 'calc(100dvh - 80px)',
  minHeight: 0,
};

const ChatRoomShell = ({ children }) => (
  <VStack
    data-testid="chat-room-shell"
    style={CHAT_ROOM_SHELL_STYLE}
    $css={{
      gap: '$0',
      width: '100%',
      margin: '0 auto',
      overflow: 'hidden',
      backgroundColor: 'var(--vapor-color-surface-normal)',
    }}
  >
    {children}
  </VStack>
);

export const ChatRoomLoadingShell = () => (
  <ChatRoomShell>
    <Flex
      className="flex-1 min-h-0"
      style={{ textAlign: 'center' }}
      $css={{
        gap: '$100',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Spinner
        size="lg"
        colorPalette="primary"
        aria-label="채팅방 연결 중"
      />
      <Text typography="heading5">채팅방 연결 중...</Text>
    </Flex>
  </ChatRoomShell>
);

const ChatRoomView = ({ roomId, onNavigate, onReplace, asPath }) => {
  const {
    room,
    messages,
    connected,
    connectionStatus,
    messageLoadError,
    retryMessageLoad,
    currentUser,
    fileInputRef,
    handleMessageSubmit,
    loading,
    error,
    handleReactionAdd,
    handleReactionRemove,
    loadingMessages,
    hasMoreMessages,
    handleLoadMore,
  } = useChatRoom({ roomId, onNavigate, onReplace, asPath });

  const renderErrorState = () => (
    <ChatRoomShell>
      <Flex
        className="flex-1 min-h-0"
        $css={{
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <ConnectionErrorBanner
          message={error || '채팅방을 불러오는데 실패했습니다.'}
        />
        <Button onClick={() => window.location.reload()}>다시 시도</Button>
      </Flex>
    </ChatRoomShell>
  );

  if (error) {
    return renderErrorState();
  }

  if (loading || !room) {
    return <ChatRoomLoadingShell />;
  }

  return (
    <ChatRoomShell>
      {/* 채팅방 정보 (참여자 목록 및 연결 상태) */}
      <ChatRoomInfo room={room} connectionStatus={connectionStatus} />

      {/* 메시지 영역 */}
      <VStack
        className="flex-1"
        style={{ position: 'relative' }}
        $css={{
          overflow: 'hidden',
          minHeight: '0',
        }}
      >
        {messageLoadError && (
          <div
            data-testid="message-load-error-overlay"
            style={{
              position: 'absolute',
              top: 'var(--vapor-space-200)',
              left: 'var(--vapor-space-300)',
              right: 'var(--vapor-space-300)',
              zIndex: 2,
              display: 'flex',
              justifyContent: 'center',
            }}
          >
            <Callout.Root colorPalette="danger">
              <Callout.Icon>
                <ErrorCircleOutlineIcon className="w-5 h-5" />
              </Callout.Icon>
              <span>메시지 로딩 중 오류가 발생했습니다.</span>
              <Button variant="outline" size="sm" onClick={retryMessageLoad}>
                메시지 다시 로드
              </Button>
            </Callout.Root>
          </div>
        )}

        <ChatMessages
          messages={messages}
          currentUser={currentUser}
          room={room}
          onReactionAdd={handleReactionAdd}
          onReactionRemove={handleReactionRemove}
          loadingMessages={loadingMessages}
          hasMoreMessages={hasMoreMessages}
          onLoadMore={handleLoadMore}
        />
      </VStack>

      {/* 입력 영역 */}
      <ChatInput
        onSubmit={handleMessageSubmit}
        fileInputRef={fileInputRef}
        disabled={connectionStatus !== 'connected'}
        room={room}
      />
    </ChatRoomShell>
  );
};

export default ChatRoomView;
