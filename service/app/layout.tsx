import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "명문가 | 종중 관리",
  description: "종원, 회의, 문서와 재산을 관리하는 명문가 시연 앱.",
  icons: {
    icon: "/favicon.svg",
    shortcut: "/favicon.svg",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko">
      <body className="antialiased">{children}</body>
    </html>
  );
}
