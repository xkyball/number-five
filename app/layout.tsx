import type { Metadata } from 'next';
import { headers } from 'next/headers';
import './globals.css';

export const metadata: Metadata = {
  metadataBase: new URL('https://no5.christophhoffmann.net'),
  title: { default: 'Number Five · Offene Technik fürs Boot', template: '%s · Number Five' },
  description: 'Dein Boot. Deine Hardware. Ein gemeinsames System. Ein offener Smart-Boat-Baukasten mit nativen Apps, gemeinsamen Diensten und Technik, die mit deinen Ideen wächst.',
  icons: { icon: '/favicon.svg' },
  verification: { google: 'AUi57fBeG3Fx6FllVkv3efm4I91wek8ZZLxjN8eel_Q', other: { 'msvalidate.01': 'BA04CCD40EABBE3C065C0A227903EAD5' } },
};
export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const h = await headers();
  return <html lang={h.get('x-number-five-language') === 'en' ? 'en' : 'de'}><body>{children}</body></html>;
}
