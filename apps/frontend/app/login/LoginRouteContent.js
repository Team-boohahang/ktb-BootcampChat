'use client';

import { useEffect } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import LoginPage from '@/components/LoginPage';
import { getSafeRedirectPath } from '@/lib/auth/safeRedirect';

const LoginRouteContent = () => {
  const router = useRouter();
  const searchParams = useSearchParams();
  const queryString = searchParams.toString();

  useEffect(() => {
    // 네트워크 내비게이션 없이 주소만 정규화한다. `/login` 문서의 로드를
    // 중단하지 않으므로 Playwright page.goto와 리다이렉트가 경합하지 않는다.
    const canonicalUrl = queryString ? `/?${queryString}` : '/';
    window.history.replaceState(window.history.state, '', canonicalUrl);
  }, [queryString]);

  return (
    <LoginPage
      router={router}
      redirectUrl={getSafeRedirectPath(searchParams.get('redirect'))}
    />
  );
};

export default LoginRouteContent;
