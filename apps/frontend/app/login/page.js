import LoginRouteContent from './LoginRouteContent';
import { getSafeRedirectPath } from '@/lib/auth/safeRedirect';

const appendSearchParam = (searchParams, key, value) => {
  if (Array.isArray(value)) {
    value.forEach((item) => searchParams.append(key, item));
    return;
  }

  if (typeof value === 'string') {
    searchParams.set(key, value);
  }
};

const LoginRoutePage = async ({ searchParams }) => {
  const resolvedSearchParams = (await searchParams) || {};
  const query = new URLSearchParams();

  Object.entries(resolvedSearchParams).forEach(([key, value]) => {
    appendSearchParam(query, key, value);
  });

  const redirectParam = resolvedSearchParams.redirect;
  const redirectUrl = getSafeRedirectPath(
    Array.isArray(redirectParam) ? redirectParam[0] : redirectParam
  );

  return (
    <LoginRouteContent
      queryString={query.toString()}
      redirectUrl={redirectUrl}
    />
  );
};

export default LoginRoutePage;
