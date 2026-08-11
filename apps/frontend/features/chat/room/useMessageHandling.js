import { useCallback, useEffect, useRef, useState } from 'react';
import { Toast } from '@/components/Toast';
import { createClientMessageId } from '@/lib/socket/clientMessageId';
import socketClient from '@/lib/socket/socketClient';
import { useChatFileUpload } from '../files/useChatFileUpload';

export const useMessageHandling = (
  currentUser,
  roomId,
  handleSessionError,
  messages = [],
  loadingMessages = false,
  setLoadingMessages,
  socketRef,
) => {
  const [sendingRoomId, setSendingRoomId] = useState(null);
  const sending = sendingRoomId === roomId;
  const activeSubmissionRef = useRef(null);
  const abortControllerRef = useRef(null);
  const mountedRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;
    activeSubmissionRef.current = null;
    abortControllerRef.current = new AbortController();
    return () => {
      mountedRef.current = false;
      activeSubmissionRef.current = null;
      abortControllerRef.current?.abort();
      abortControllerRef.current = null;
    };
  }, [roomId]);

 const {
   filePreview,
   uploading,
   uploadProgress,
   uploadError,
   setFilePreview,
   setUploading,
   setUploadProgress,
   setUploadError,
   resetFileUpload,
   uploadChatFile
 } = useChatFileUpload();

  const getRoomSocket = useCallback(() => socketRef?.current ?? null, [socketRef]);

  const canSendOnRoomSocket = useCallback(() => {
    if (socketRef) {
      return Boolean(getRoomSocket()?.connected);
    }

    return socketClient.canSend();
  }, [getRoomSocket, socketRef]);

  const handleLoadMore = useCallback(() => {
    if (!canSendOnRoomSocket()) {
      return;
    }

    if (loadingMessages) {
      return;
    }

    // 메시지는 상태 병합 단계에서 시간순으로 유지한다.
    const oldestMessage = messages[0];
    const beforeTimestamp = oldestMessage?.timestamp;

    if (!beforeTimestamp) {
      return;
    }

    setLoadingMessages(true);

    // Socket.IO 이벤트만 발행 - 응답은 useChatRoom의 previousMessagesLoaded 이벤트 핸들러에서 처리
    socketClient.fetchPreviousMessages({
      roomId: roomId,
      before: beforeTimestamp,
      limit: 30
    }, getRoomSocket());
  }, [roomId, loadingMessages, messages, setLoadingMessages, canSendOnRoomSocket, getRoomSocket]);

 const handleMessageSubmit = useCallback(async (messageData) => {
   if (activeSubmissionRef.current) {
     return false;
   }

   const roomSocket = getRoomSocket();
   if (!canSendOnRoomSocket() || !currentUser) {
     Toast.error('채팅 서버와 연결이 끊어졌습니다.');
     return false;
   }

   if (!roomId) {
     Toast.error('채팅방 정보를 찾을 수 없습니다.');
     return false;
   }

   const isFileMessage = messageData.type === 'file';
   if (!isFileMessage && !messageData.content?.trim()) {
     return false;
   }

   const submissionToken = Symbol('message-submission');
   const clientMessageId = createClientMessageId();
   activeSubmissionRef.current = submissionToken;
   setSendingRoomId(roomId);

   try {
      if (isFileMessage) {
        const uploadResponse = await uploadChatFile(
          messageData.fileData.file,
          currentUser
        );

       await socketClient.sendChatMessageAndWait({
         room: roomId,
         type: 'file',
         content: messageData.content || '',
         clientMessageId,
         fileData: {
           _id: uploadResponse.data.file._id,
           filename: uploadResponse.data.file.filename,
           originalname: uploadResponse.data.file.originalname,
           mimetype: uploadResponse.data.file.mimetype,
           size: uploadResponse.data.file.size
         }
       }, roomSocket, { signal: abortControllerRef.current?.signal });

       resetFileUpload();

     } else {
       await socketClient.sendChatMessageAndWait({
         room: roomId,
         type: 'text',
         content: messageData.content.trim(),
         clientMessageId,
       }, roomSocket, { signal: abortControllerRef.current?.signal });
     }

     return true;

   } catch (error) {
     if (error?.name === 'AbortError') {
       return false;
     }

     if (error.message?.includes('세션') ||
         error.message?.includes('인증') ||
         error.message?.includes('토큰')) {
       await handleSessionError?.();
       return false;
     }

     // 서버가 거부한 메시지는 onError 핸들러가 이미 토스트로 알렸다.
     // 여기서 또 띄우면 같은 사유의 토스트가 두 개 뜬다.
     if (error?.code !== 'MESSAGE_REJECTED') {
       Toast.error(error.message || '메시지 전송 중 오류가 발생했습니다.');
     }
     if (messageData.type === 'file') {
       setUploadError(error.message);
       setUploading(false);
     }
     return false;
   } finally {
     if (activeSubmissionRef.current === submissionToken) {
       activeSubmissionRef.current = null;
       if (mountedRef.current) {
         setSendingRoomId(null);
       }
     }
   }
 }, [currentUser, roomId, handleSessionError, uploadChatFile, resetFileUpload, setUploadError, setUploading, canSendOnRoomSocket, getRoomSocket]);

 const removeFilePreview = useCallback(() => {
   resetFileUpload();
 }, [resetFileUpload]);

 return {
   filePreview,
   uploading,
   uploadProgress,
   uploadError,
   sending,
   setFilePreview,
   handleMessageSubmit,
   handleLoadMore,
   removeFilePreview,
 };
};

export default useMessageHandling;
