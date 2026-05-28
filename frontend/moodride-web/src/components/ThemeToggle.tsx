"use client";

import { useEffect, useState } from "react";
import { applyTheme, resolveInitialTheme, storeTheme, type Theme } from "@/lib/theme";

interface Props {
  compact?: boolean;
}

export function ThemeToggle({ compact }: Props) {
  const [theme, setTheme] = useState<Theme>("dark");
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    const initial = resolveInitialTheme();
    setTheme(initial);
    applyTheme(initial);
    setMounted(true);
  }, []);

  const toggle = () => {
    const next: Theme = theme === "dark" ? "light" : "dark";
    setTheme(next);
    applyTheme(next);
    storeTheme(next);
  };

  const label = theme === "dark" ? "Dark" : "Light";

  return (
    <button
      type="button"
      className={`theme-toggle ${compact ? "theme-toggle-compact" : ""}`}
      onClick={toggle}
      aria-label={`Switch theme (currently ${label})`}
      title="Toggle theme"
      disabled={!mounted}
    >
      <span className="theme-toggle-icon" aria-hidden="true">
        {theme === "dark" ? "◐" : "☼"}
      </span>
      <span className="theme-toggle-label">{label}</span>
    </button>
  );
}
