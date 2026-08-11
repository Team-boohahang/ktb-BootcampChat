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

  const renderLoadingState = () => (
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

  const renderContent = () => {
    // 재연결 중에는 메시지를 그대로 두고 ChatRoomInfo 의 배지로만 알린다.
    // 복구 가능한 상태를 오류 화면으로 덮으면 실제 장애와 구분되지 않는다.
    if (messageLoadError) {
      return (
        <div className="d-flex flex-column align-items-center justify-content-center p-4">
          <Callout.Root
            colorPalette="danger"
            className="mb-4 d-flex align-items-center"
          >
            <Callout.Icon>
              <ErrorCircleOutlineIcon className="w-5 h-5 me-2" />
            </Callout.Icon>
            <span>메시지 로딩 중 오류가 발생했습니다.</span>
          </Callout.Root>
          <Button onClick={retryMessageLoad}>메시지 다시 로드</Button>
        </div>
      );
    }

    return (
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
    );
  };

  if (error) {
    return renderErrorState();
  }

  if (loading || !room) {
    return renderLoadingState();
  }

  return (
    <ChatRoomShell>
      {/* 채팅방 정보 (참여자 목록 및 연결 상태) */}
      <ChatRoomInfo room={room} connectionStatus={connectionStatus} />

      {/* 메시지 영역 */}
      <VStack
        className="flex-1"
        $css={{
          overflow: 'hidden',
          minHeight: '0',
        }}
      >
        {renderContent()}
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
