'use client';

import { useEffect } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import ChatHeader from '@/components/ChatHeader';
import { useAuth } from '@/contexts/AuthContext';
import ChatRoomsView from '@/features/chat/rooms/ChatRoomsView';

export default function ChatPage() {
  const router = useRouter();
  const pathname = usePathname();
  const { isAuthenticated, isLoading } = useAuth();

  useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      router.replace(`/?redirect=${pathname}`);
    }
  }, [isAuthenticated, isLoading, pathname, router]);

  if (!isLoading && !isAuthenticated) {
    return null;
  }

  // 인증 정보 복원 전에도 방 목록의 안정적인 셸을 서버 HTML에 포함한다.
  // ChatRoomsView는 사용자가 없을 때 네트워크 요청을 시작하지 않는다.
  return (
    <>
      <ChatHeader />
      <ChatRoomsView router={router} />
    </>
  );
}
