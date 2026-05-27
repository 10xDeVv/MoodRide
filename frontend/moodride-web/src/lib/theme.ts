export type Theme = "dark" | "light";

const STORAGE_KEY = "moodride_theme";

export function getStoredTheme(): Theme | null {
  if (typeof window === "undefined") {
    return null;
  }

  const value = window.localStorage.getItem(STORAGE_KEY);
  if (value === "dark" || value === "light") {
    return value;
  }

  return null;
}

export function storeTheme(theme: Theme) {
  window.localStorage.setItem(STORAGE_KEY, theme);
}

export function getSystemTheme(): Theme {
  if (typeof window === "undefined") {
    return "dark";
  }

  return window.matchMedia?.("(prefers-color-scheme: light)").matches ? "light" : "dark";
}

export function resolveInitialTheme(): Theme {
  const stored = getStoredTheme();
  return stored ?? getSystemTheme();
}

export function applyTheme(theme: Theme) {
  if (typeof document === "undefined") {
    return;
  }

  document.documentElement.dataset.theme = theme;
}
