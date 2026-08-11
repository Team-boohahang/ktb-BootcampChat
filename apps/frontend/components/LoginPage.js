import React, { useEffect, useState } from 'react';
import { ErrorCircleIcon } from '@vapor-ui/icons';
import { useAuth } from '@/contexts/AuthContext';
import authService from '@/services/authService';
import {
  Box,
  Button,
  Callout,
  Field,
  Form,
  HStack,
  Text,
  TextInput,
  VStack,
} from '@vapor-ui/core';

const LoginPage = ({ router, redirectUrl = '/chat' }) => {
  const [formData, setFormData] = useState({ email: '', password: '' });
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);
  const [serverNotice, setServerNotice] = useState(null);
  const { login } = useAuth();

  useEffect(() => {
    if (typeof window === 'undefined') return undefined;

    let active = true;
    const timer = setTimeout(async () => {
      try {
        await authService.checkServerConnection();
      } catch {
        if (!active) return;

        setServerNotice(
          process.env.NODE_ENV === 'development'
            ? '개발 환경: 서버 연결을 확인할 수 없지만 계속 진행합니다. 백엔드 서버가 실행 중인지 확인해주세요.'
            : '서버와의 연결을 확인할 수 없습니다. 로그인을 시도해보세요. 문제가 지속되면 새로고침해주세요.'
        );
      }
    }, 100);

    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, []);

  const handleSubmit = async (event) => {
    event.preventDefault();
    setLoading(true);
    setError(null);

    try {
      await login({
        email: formData.email.trim(),
        password: formData.password,
      });
      router.push(redirectUrl);
    } catch (err) {
      setError(err.message || '로그인 처리 중 오류가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center p-(--vapor-space-300) bg-(--vapor-color-background)">
      <VStack
        $css={{
          gap: '$250',
          width: '400px',
          padding: '$300',
          borderRadius: '$300',
          border: '1px solid var(--vapor-color-border-normal)',
        }}
        render={<Form onSubmit={handleSubmit} />}
      >
        <div className="text-center mb-4">
          <img src="/images/logo-h.png" className="w-1/2 mx-auto" alt="KTB Chat 로고" />
        </div>

        {serverNotice && (
          <Callout.Root colorPalette="warning" data-testid="server-status-message">
            <Callout.Icon><ErrorCircleIcon /></Callout.Icon>
            {serverNotice}
          </Callout.Root>
        )}

        {error && (
          <Callout.Root colorPalette="warning" data-testid="login-error-message">
            <Callout.Icon><ErrorCircleIcon /></Callout.Icon>
            {error}
          </Callout.Root>
        )}

        <VStack $css={{ gap: '$400' }}>
          <VStack $css={{ gap: '$200' }}>
            <Field.Root>
              <Box
                render={<Field.Label />}
                $css={{ flexDirection: 'column' }}
                style={{ fontSize: '14px', fontWeight: '500', marginBottom: '8px' }}
              >
                이메일
                <TextInput
                  id="login-email"
                  size="lg"
                  type="email"
                  required
                  disabled={loading}
                  value={formData.email}
                  onValueChange={(value) => setFormData(prev => ({ ...prev, email: value }))}
                  placeholder="이메일을 입력하세요"
                  data-testid="login-email-input"
                />
              </Box>
              <Field.Error match="valueMissing">이메일을 입력해주세요.</Field.Error>
              <Field.Error match="typeMismatch">유효한 이메일 형식이 아닙니다.</Field.Error>
            </Field.Root>

            <Field.Root>
              <Box
                render={<Field.Label />}
                $css={{ flexDirection: 'column' }}
                style={{ fontSize: '14px', fontWeight: '500', marginBottom: '8px' }}
              >
                비밀번호
                <TextInput
                  id="login-password"
                  size="lg"
                  type="password"
                  required
                  disabled={loading}
                  value={formData.password}
                  onValueChange={(value) => setFormData(prev => ({ ...prev, password: value }))}
                  placeholder="비밀번호를 입력하세요"
                  data-testid="login-password-input"
                />
              </Box>
              <Field.Error match="valueMissing">비밀번호를 입력해주세요.</Field.Error>
            </Field.Root>
          </VStack>

          <Button type="submit" size="lg" disabled={loading} data-testid="login-submit-button">
            {loading ? '로그인 중...' : '로그인'}
          </Button>
        </VStack>

        <HStack $css={{ justifyContent: 'center' }}>
          <Text typography="body2">계정이 없으신가요?</Text>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => router.push('/register')}
            disabled={loading}
          >
            회원가입
          </Button>
        </HStack>
      </VStack>
    </div>
  );
};

export default LoginPage;
