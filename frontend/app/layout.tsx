import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "智阅 Wisread",
  description: "AI PDF 智能阅读与问答助手"
};

export default function RootLayout({
  children
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
