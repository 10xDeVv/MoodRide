import "./globals.css";
import type { Metadata } from "next";
import type { ReactNode } from "react";
import { Bebas_Neue, JetBrains_Mono, Manrope, Playfair_Display, Space_Grotesk } from "next/font/google";

const manrope = Manrope({
  subsets: ["latin"],
  variable: "--font-manrope",
  display: "swap"
});

const spaceGrotesk = Space_Grotesk({
  subsets: ["latin"],
  variable: "--font-space",
  display: "swap"
});

const bebasNeue = Bebas_Neue({
  subsets: ["latin"],
  weight: "400",
  variable: "--font-display",
  display: "swap"
});

const jetBrainsMono = JetBrains_Mono({
  subsets: ["latin"],
  variable: "--font-mono",
  display: "swap"
});

const playfair = Playfair_Display({
  subsets: ["latin"],
  variable: "--font-serif",
  display: "swap"
});

export const metadata: Metadata = {
  title: "MoodRide | Scenic Route Intelligence",
  description: "Generate scenic loops for drives, walks, and rides, starting with Canada-wide driving routes."
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body className={`${manrope.variable} ${spaceGrotesk.variable} ${bebasNeue.variable} ${jetBrainsMono.variable} ${playfair.variable}`}>
        {children}
      </body>
    </html>
  );
}

