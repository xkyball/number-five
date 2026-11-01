import { NextResponse, type NextRequest } from 'next/server';
export function middleware(request: NextRequest) {
  const h = new Headers(request.headers);
  h.set('x-number-five-language', request.nextUrl.pathname.startsWith('/en') ? 'en' : 'de');
  const response = NextResponse.next({ request: { headers: h } });
  response.headers.set('X-Content-Type-Options', 'nosniff');
  response.headers.set('Referrer-Policy', 'strict-origin-when-cross-origin');
  response.headers.set('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
  if (/^\/(admin|api\/inbox|api\/contact)/.test(request.nextUrl.pathname)) {
    response.headers.set('Cache-Control', 'private, no-store');
    response.headers.set('X-Robots-Tag', 'noindex, nofollow');
  }
  return response;
}
