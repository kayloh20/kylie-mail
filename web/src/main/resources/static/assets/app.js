"use strict";

// Kylie Mail's sign-in pages. Passkeys sign you in, and the secret each passkey's PRF extension
// produces unlocks the key that seals your app-specific password on the server.

const views = ["loading", "unsupported", "setup", "codes", "signin", "home"];
const $ = (id) => document.getElementById(id);

function show(view) {
  for (const v of views) $(v).hidden = v !== view;
  const first = $(view).querySelector("input, button");
  if (first && view !== "home") first.focus();
}

function setError(container, message) {
  const el = container.querySelector(".error");
  if (el) el.textContent = message || "";
}

// base64url <-> ArrayBuffer

function fromB64url(text) {
  const b64 = text.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(text.length / 4) * 4, "=");
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes.buffer;
}

function toB64url(buffer) {
  const bytes = new Uint8Array(buffer);
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// Talking to the server

async function api(method, path, body) {
  const response = await fetch(path, {
    method,
    credentials: "same-origin",
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data.error || "Something went wrong. Try again.");
    error.status = response.status;
    throw error;
  }
  return data;
}

// Passkeys

function creationOptions(o) {
  return {
    ...o,
    challenge: fromB64url(o.challenge),
    user: { ...o.user, id: fromB64url(o.user.id) },
    excludeCredentials: o.excludeCredentials.map((c) => ({ ...c, id: fromB64url(c.id) })),
    extensions: { prf: { eval: { first: fromB64url(o.extensions.prf.eval.first) } } },
  };
}

function requestOptions(o) {
  const evalByCredential = {};
  for (const [id, values] of Object.entries(o.extensions.prf.evalByCredential)) {
    evalByCredential[id] = { first: fromB64url(values.first) };
  }
  return {
    ...o,
    challenge: fromB64url(o.challenge),
    allowCredentials: o.allowCredentials.map((c) => ({ ...c, id: fromB64url(c.id) })),
    extensions: { prf: { evalByCredential } },
  };
}

const NO_PRF =
  "This passkey can't lock your mail, so it wasn't added. Use one saved in iCloud Keychain, " +
  "Google Password Manager or a recent security key. You can delete the Kylie Mail passkey it just saved.";

/** Creates a passkey and gets its PRF secret, asking a second time if the browser only gives it on sign-in. */
async function createPasskey(options) {
  const credential = await navigator.credentials.create({ publicKey: creationOptions(options) });
  const prf = credential.getClientExtensionResults().prf;
  let secret = prf && prf.results && prf.results.first;
  if (!secret) {
    if (prf && prf.enabled === false) throw new Error(NO_PRF);
    // Some browsers turn the PRF on when creating a passkey but only answer when it's used.
    const check = await navigator.credentials.get({
      publicKey: {
        challenge: crypto.getRandomValues(new Uint8Array(32)),
        rpId: options.rp.id,
        allowCredentials: [{ type: "public-key", id: credential.rawId }],
        userVerification: "required",
        extensions: { prf: { eval: { first: fromB64url(options.extensions.prf.eval.first) } } },
      },
    });
    const again = check.getClientExtensionResults().prf;
    secret = again && again.results && again.results.first;
    if (!secret) throw new Error(NO_PRF);
  }
  return {
    credential: {
      id: credential.id,
      response: {
        clientDataJSON: toB64url(credential.response.clientDataJSON),
        attestationObject: toB64url(credential.response.attestationObject),
      },
    },
    prf: toB64url(secret),
  };
}

async function usePasskey(options) {
  const credential = await navigator.credentials.get({ publicKey: requestOptions(options) });
  const prf = credential.getClientExtensionResults().prf;
  const secret = prf && prf.results && prf.results.first;
  if (!secret) throw new Error("This passkey didn't unlock your mail. Try another one, or a recovery code.");
  const r = credential.response;
  return {
    credential: {
      id: credential.id,
      response: {
        clientDataJSON: toB64url(r.clientDataJSON),
        authenticatorData: toB64url(r.authenticatorData),
        signature: toB64url(r.signature),
        userHandle: r.userHandle ? toB64url(r.userHandle) : null,
      },
    },
    prf: toB64url(secret),
  };
}

/** Turns a browser passkey error into words. */
function explain(error) {
  if (error && error.name === "NotAllowedError") {
    return "No passkey was used. The request was cancelled or timed out, or this device has no Kylie Mail passkey.";
  }
  if (error && error.name === "InvalidStateError") return "That passkey is already added.";
  return (error && error.message) || "Something went wrong. Try again.";
}

async function busy(button, task) {
  button.disabled = true;
  try {
    await task();
  } finally {
    button.disabled = false;
  }
}

// Screens

function showCodes(codes, then) {
  const list = $("code-list");
  list.replaceChildren(...codes.map((code) => Object.assign(document.createElement("li"), { textContent: code })));
  $("codes-saved").checked = false;
  $("codes-done").disabled = true;
  $("codes-done").onclick = then;
  show("codes");
}

async function showHome() {
  const me = await api("GET", "/api/me");
  $("me-email").textContent = me.email;
  $("recovered").hidden = !me.signedInWithRecoveryCode;
  $("codes-left").textContent = `${me.recoveryCodesLeft} recovery code${me.recoveryCodesLeft === 1 ? "" : "s"}`;
  const list = $("passkey-list");
  list.replaceChildren(
    ...me.passkeys.map((p) => {
      const item = document.createElement("li");
      const label = document.createElement("div");
      label.append(document.createTextNode(p.name));
      const detail = document.createElement("small");
      const used = p.lastUsedAt ? `last used ${new Date(p.lastUsedAt).toLocaleDateString()}` : "not used yet";
      detail.textContent = `Added ${new Date(p.createdAt).toLocaleDateString()}, ${used}${p.synced ? ", synced" : ""}`;
      label.append(detail);
      item.append(label);
      if (me.passkeys.length > 1) {
        const remove = Object.assign(document.createElement("button"), { type: "button", className: "secondary", textContent: "Remove" });
        remove.onclick = () =>
          busy(remove, async () => {
            if (!confirm(`Remove “${p.name}”? It won't be able to sign in any more.`)) return;
            await api("DELETE", `/api/passkeys/${encodeURIComponent(p.id)}`).catch((e) => setError(list.parentElement, e.message));
            await showHome();
          });
        item.append(remove);
      }
      return item;
    }),
  );
  show("home");
}

async function start() {
  if (!window.PublicKeyCredential || !navigator.credentials) return show("unsupported");
  try {
    const state = await api("GET", "/api/state");
    if (!state.hasAccount) return show("setup");
    if (state.signedIn) return await showHome();
    show("signin");
  } catch (e) {
    $("loading").querySelector("p").textContent = "Couldn't reach Kylie Mail. Reload to try again.";
  }
}

// Wiring

document.addEventListener("DOMContentLoaded", () => {
  $("setup-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const form = event.target;
    const button = form.querySelector("button");
    setError(form, "");
    busy(button, async () => {
      try {
        const fields = Object.fromEntries(new FormData(form));
        const started = await api("POST", "/api/setup/start", fields);
        const made = await createPasskey(started.options);
        const done = await api("POST", "/api/setup/finish", { pendingId: started.pendingId, ...made });
        form.reset();
        showCodes(done.recoveryCodes, showHome);
      } catch (e) {
        setError(form, explain(e));
      }
    });
  });

  $("codes-saved").addEventListener("change", (event) => {
    $("codes-done").disabled = !event.target.checked;
  });
  $("copy-codes").addEventListener("click", async () => {
    const codes = [...$("code-list").children].map((li) => li.textContent).join("\n");
    await navigator.clipboard.writeText(`Kylie Mail recovery codes (each works once)\n\n${codes}\n`);
    $("copy-codes").textContent = "Copied";
  });
  $("print-codes").addEventListener("click", () => window.print());

  $("signin-button").addEventListener("click", (event) => {
    const section = $("signin");
    setError(section, "");
    busy(event.target, async () => {
      try {
        const started = await api("POST", "/api/signin/start");
        const used = await usePasskey(started.options);
        await api("POST", "/api/signin/finish", { pendingId: started.pendingId, ...used });
        await showHome();
      } catch (e) {
        setError(section, explain(e));
      }
    });
  });

  $("recover-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const form = event.target;
    setError(form, "");
    busy(form.querySelector("button"), async () => {
      try {
        await api("POST", "/api/recover", Object.fromEntries(new FormData(form)));
        form.reset();
        await showHome();
      } catch (e) {
        setError(form, explain(e));
      }
    });
  });

  $("signout").addEventListener("click", async () => {
    await api("POST", "/api/signout").catch(() => {});
    show("signin");
  });

  $("add-passkey-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const form = event.target;
    const card = form.parentElement;
    setError(card, "");
    busy(form.querySelector("button"), async () => {
      try {
        const started = await api("POST", "/api/passkeys/start", Object.fromEntries(new FormData(form)));
        const made = await createPasskey(started.options);
        await api("POST", "/api/passkeys/finish", { pendingId: started.pendingId, ...made });
        form.reset();
        await showHome();
      } catch (e) {
        if (e.status === 401) return show("signin");
        setError(card, explain(e));
      }
    });
  });

  $("new-codes").addEventListener("click", (event) => {
    const card = event.target.parentElement;
    setError(card, "");
    if (!confirm("Make new recovery codes? Your current codes will stop working.")) return;
    busy(event.target, async () => {
      try {
        const made = await api("POST", "/api/recovery-codes");
        showCodes(made.recoveryCodes, showHome);
      } catch (e) {
        if (e.status === 401) return show("signin");
        setError(card, explain(e));
      }
    });
  });

  start();
});
