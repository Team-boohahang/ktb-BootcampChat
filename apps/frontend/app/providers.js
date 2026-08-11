'use client';

import { ThemeProvider } from '@vapor-ui/core';
import { useRouter } from 'next/navigation';
import ToastContainer from '@/components/Toast';
import { AuthProviderWithRouter } from '@/contexts/AuthContext';

export default function AppProviders({ children }) {
  const router = useRouter();

  return (
    <ThemeProvider defaultTheme="dark">
      <AuthProviderWithRouter router={router}>
        {children}
        <ToastContainer />
      </AuthProviderWithRouter>
    </ThemeProvider>
  );
}
