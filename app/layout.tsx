import type { Metadata } from 'next';
import { headers } from 'next/headers';
import './globals.css';

export const metadata: Metadata = {
  metadataBase: new URL('https://number-five.xkyball.chatgpt.site'),
  title: { default: 'Number Five · Offene Technik fürs Boot', template: '%s · Number Five' },
  description: 'Ein Boot. Offene Technik. Die Entwicklung von Android-Instrumenten, Karten, Audio und eigenem Sonar – mit Quellen, Ergebnissen und einer Einladung zum Mitbauen.',
  icons: { icon: '/favicon.svg' },
};
export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const h = await headers();
  return <html lang={h.get('x-number-five-language') === 'en' ? 'en' : 'de'}><body>{children}</body></html>;
}
