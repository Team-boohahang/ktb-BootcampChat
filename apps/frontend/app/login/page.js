import { Suspense } from 'react';
import LoginRouteContent from './LoginRouteContent';

const LoginRoutePage = () => (
  <Suspense fallback={null}>
    <LoginRouteContent />
  </Suspense>
);

export default LoginRoutePage;
