"use strict";
// Applies your Light or Dark choice before the page draws, so it never flashes the other one.
// Like the app, it follows the system unless you choose. Kept in this browser only.
try {
  const theme = localStorage.getItem("theme");
  if (theme === "light" || theme === "dark") document.documentElement.dataset.theme = theme;
} catch {
  // Storage blocked: follow the system.
}
