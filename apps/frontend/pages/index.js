import React from 'react';
import { useRouter } from 'next/router';
import { withoutAuth } from '@/contexts/AuthContext';
import LoginPage from '@/components/LoginPage';

export const Login = () => {
  const router = useRouter();

  return (
    <LoginPage
      router={router}
      redirectUrl={router.query.redirect || '/chat'}
    />
  );
};

export default withoutAuth(Login);
