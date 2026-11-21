"use strict";

// The web app's entry point. Sign-in, the mailbox and the keyboard shortcuts build on this.
document.addEventListener("DOMContentLoaded", () => {
  if (!window.PublicKeyCredential) {
    document.getElementById("status").textContent =
      "This browser can't use passkeys, which Kylie Mail needs for signing in.";
  }
});
