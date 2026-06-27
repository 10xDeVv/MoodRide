import "./globals.css";
import type { Metadata } from "next";
import type { ReactNode } from "react";
import localFont from "next/font/local";

const displayFont = localFont({
  src: [
    { path: "../assets/fonts/Anton-Regular.ttf", weight: "900", style: "normal" },
  ],
  variable: "--font-display",
  display: "swap"
});

const dmSans = localFont({
  src: [
    { path: "../assets/fonts/DMSans-Regular.ttf", weight: "400", style: "normal" },
    { path: "../assets/fonts/DMSans-Medium.ttf", weight: "500", style: "normal" },
    { path: "../assets/fonts/DMSans-SemiBold.ttf", weight: "600", style: "normal" },
    { path: "../assets/fonts/DMSans-Bold.ttf", weight: "700", style: "normal" },
    { path: "../assets/fonts/DMSans-ExtraBold.ttf", weight: "800", style: "normal" },
    { path: "../assets/fonts/DMSans-Black.ttf", weight: "900", style: "normal" },
  ],
  variable: "--font-body",
  display: "swap"
});

export const metadata: Metadata = {
  title: "Wayward — Scenic Route Generator",
  description: "Generate scenic loop drives from a starting point. Compare route personalities, then launch navigation or export GPX."
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" className={`${displayFont.variable} ${dmSans.variable}`}>
      <body>{children}</body>
    </html>
  );
}
