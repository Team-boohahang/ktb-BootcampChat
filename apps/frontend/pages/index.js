import React from 'react';
import { useRouter } from 'next/router';
import { withoutAuth } from '@/contexts/AuthContext';
import LoginPage from '@/components/LoginPage';
import { getSafeRedirectPath } from '@/lib/auth/safeRedirect';

export const Login = () => {
  const router = useRouter();

  return (
    <LoginPage
      router={router}
      redirectUrl={getSafeRedirectPath(router.query.redirect)}
    />
  );
};

export default withoutAuth(Login);
