'use client';

import { useEffect } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import ChatHeader from '@/components/ChatHeader';
import { useAuth } from '@/contexts/AuthContext';
import ChatRoomView, {
  ChatRoomLoadingShell,
} from '@/features/chat/room/ChatRoomView';
import { useRoomId } from '@/hooks/useRoomId';

export default function ChatRoomPage() {
  const router = useRouter();
  const pathname = usePathname();
  const { isAuthenticated, isLoading } = useAuth();
  const roomId = useRoomId();

  useEffect(() => {
    if (!isLoading && !isAuthenticated) {
      router.replace(`/?redirect=${pathname}`);
    }
  }, [isAuthenticated, isLoading, pathname, router]);

  if (!isLoading && !isAuthenticated) {
    return null;
  }

  if (isLoading) {
    return (
      <>
        <ChatHeader />
        <ChatRoomLoadingShell />
      </>
    );
  }

  return (
    <>
      <ChatHeader />
      <ChatRoomView
        roomId={roomId}
        onNavigate={router.push}
        onReplace={router.replace}
        asPath={pathname}
      />
    </>
  );
}
