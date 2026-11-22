"use strict";

// Kylie Mail in the browser. Passkeys sign you in, and the secret each passkey's PRF extension
// produces unlocks the key that seals your app-specific password on the server. Signed in, you
// get the mailbox: folders, messages, and each email in a sandboxed frame.

const views = ["loading", "unsupported", "setup", "codes", "signin"];
const $ = (id) => document.getElementById(id);

/** Shows one sign-in screen, or the mailbox when [view] is "app". */
function show(view) {
  $("auth").hidden = view === "app";
  $("app").hidden = view !== "app";
  for (const v of views) $(v).hidden = v !== view;
  if (view === "app") return;
  if ($("settings").open) $("settings").close();
  const first = $(view).querySelector("input, button");
  if (first) first.focus();
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
    // Signed out on the server (expired, or it restarted): back to the sign-in screen.
    if (response.status === 401 && !$("app").hidden) show("signin");
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

// Sign-in screens

function showCodes(codes, then) {
  if ($("settings").open) $("settings").close();
  const list = $("code-list");
  list.replaceChildren(...codes.map((code) => Object.assign(document.createElement("li"), { textContent: code })));
  $("codes-saved").checked = false;
  $("codes-done").disabled = true;
  $("codes-done").onclick = then;
  $("copy-codes").textContent = "Copy";
  show("codes");
}

async function start() {
  if (!window.PublicKeyCredential || !navigator.credentials) return show("unsupported");
  try {
    const state = await api("GET", "/api/state");
    if (!state.hasAccount) return show("setup");
    if (state.signedIn) return await openMailbox();
    show("signin");
  } catch (e) {
    $("loading").querySelector("p").textContent = "Couldn't reach Kylie Mail. Reload to try again.";
  }
}

// Settings: passkeys and recovery codes

async function loadSettings() {
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
            await loadSettings();
          });
        item.append(remove);
      }
      return item;
    }),
  );
  return me;
}

// The mailbox

const mail = {
  folders: [],
  folder: null, // folder id
  validity: 0,
  messages: [],
  before: null,
  selected: null, // uid
  detail: null,
  loadToken: 0,
};

const shortDate = new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" });
const longDate = new Intl.DateTimeFormat(undefined, { dateStyle: "full", timeStyle: "short" });
const timeOnly = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" });
const oldDate = new Intl.DateTimeFormat(undefined, { year: "2-digit", month: "numeric", day: "numeric" });

function listDate(ms) {
  if (!ms) return "";
  const d = new Date(ms);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) return timeOnly.format(d);
  if (d.getFullYear() === now.getFullYear()) return shortDate.format(d);
  return oldDate.format(d);
}

function formatSize(bytes) {
  if (bytes <= 0) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function el(tag, props = {}, ...children) {
  const node = Object.assign(document.createElement(tag), props);
  node.append(...children.filter((c) => c !== null && c !== undefined));
  return node;
}

async function openMailbox() {
  show("app");
  loadSettings().catch(() => {});
  await loadFolders();
  const inbox = mail.folders.find((f) => f.role === "inbox") || mail.folders[0];
  if (inbox) await openFolder(inbox.id);
}

async function loadFolders() {
  mail.folders = await api("GET", "/api/folders");
  renderFolders();
}

function renderFolders() {
  $("folder-list").replaceChildren(
    ...mail.folders.map((f) => {
      const button = el(
        "button",
        { type: "button", title: f.name },
        el("span", { textContent: f.name }),
        f.unread > 0 && f.role !== "sent" && f.role !== "drafts" && f.role !== "trash" ? el("span", { className: "count", textContent: String(f.unread) }) : null,
      );
      button.style.paddingLeft = `${12 + f.depth * 14}px`;
      button.setAttribute("aria-current", String(f.id === mail.folder));
      button.onclick = () => openFolder(f.id);
      return el("li", {}, button);
    }),
  );
  $("folder-select").replaceChildren(
    ...mail.folders.map((f) =>
      el("option", { value: f.id, selected: f.id === mail.folder, textContent: f.unread > 0 ? `${f.name} (${f.unread})` : f.name }),
    ),
  );
}

async function openFolder(id) {
  const token = ++mail.loadToken;
  mail.folder = id;
  mail.messages = [];
  mail.selected = null;
  closeMessage();
  renderFolders();
  const folder = mail.folders.find((f) => f.id === id);
  $("folder-title").textContent = folder ? folder.name : id;
  $("message-list").replaceChildren();
  $("load-older").hidden = true;
  $("list-status").textContent = "Loading…";
  try {
    const page = await api("GET", `/api/messages?folder=${encodeURIComponent(id)}`);
    if (token !== mail.loadToken) return;
    mail.validity = page.validity;
    mail.messages = page.messages;
    mail.before = page.before;
    $("list-status").textContent = page.messages.length ? "" : "No messages here.";
    renderMessages();
  } catch (e) {
    if (token === mail.loadToken) $("list-status").textContent = e.message;
  }
}

async function loadOlder() {
  if (!mail.before) return;
  const token = mail.loadToken;
  await busy($("load-older"), async () => {
    try {
      const page = await api("GET", `/api/messages?folder=${encodeURIComponent(mail.folder)}&before=${mail.before}`);
      if (token !== mail.loadToken) return;
      const known = new Set(mail.messages.map((m) => m.uid));
      mail.messages.push(...page.messages.filter((m) => !known.has(m.uid)));
      mail.before = page.before;
      renderMessages();
    } catch (e) {
      $("list-status").textContent = e.message;
    }
  });
}

function renderMessages() {
  const list = $("message-list");
  list.replaceChildren(
    ...mail.messages.map((m) => {
      const marks = [m.flagged ? "⚑" : "", m.hasAttachments ? "📎" : "", m.answered ? "↩" : ""].join(" ").trim();
      const item = el(
        "li",
        { id: `m-${m.uid}`, className: m.seen ? "" : "unread" },
        el("span", { className: "from", textContent: m.fromName || m.fromAddress || "(unknown sender)" }),
        el("span", { className: "date", textContent: listDate(m.date) }),
        el("span", { className: "subject", textContent: m.subject }, marks ? el("span", { className: "marks", textContent: ` ${marks}` }) : null),
      );
      item.setAttribute("role", "option");
      item.setAttribute("aria-selected", String(m.uid === mail.selected));
      item.onclick = () => selectMessage(m.uid);
      return item;
    }),
  );
  $("load-older").hidden = !mail.before;
}

function markSelected() {
  for (const item of $("message-list").children) {
    item.setAttribute("aria-selected", String(item.id === `m-${mail.selected}`));
  }
  const current = $(`m-${mail.selected}`);
  if (current) current.scrollIntoView({ block: "nearest" });
}

function bodyUrl(detail, images) {
  const q = `folder=${encodeURIComponent(detail.folder)}&validity=${detail.validity}&uid=${detail.summary.uid}`;
  return `/api/message/body?${q}${images ? "&images=1" : ""}`;
}

async function selectMessage(uid) {
  mail.selected = uid;
  markSelected();
  $("app").classList.add("reading");
  $("reader-empty").hidden = true;
  $("message").hidden = false;
  const summary = mail.messages.find((m) => m.uid === uid);
  $("message-subject").textContent = summary ? summary.subject : "";
  $("message-from").textContent = summary ? summary.fromName : "";
  $("message-address").textContent = summary && summary.fromAddress !== summary.fromName ? `<${summary.fromAddress}>` : "";
  $("message-date").textContent = summary && summary.date ? longDate.format(new Date(summary.date)) : "";
  $("message-to").textContent = "";
  $("images-bar").hidden = true;
  $("attachments").replaceChildren();
  $("message-status").textContent = "Loading…";
  $("message-body").hidden = true;
  $("message-body").removeAttribute("src");
  try {
    const detail = await api(
      "GET",
      `/api/message?folder=${encodeURIComponent(mail.folder)}&validity=${mail.validity}&uid=${uid}`,
    );
    if (mail.selected !== uid) return;
    mail.detail = detail;
    showMessage(detail);
    if (summary && !summary.seen) markSeen([uid], true);
  } catch (e) {
    if (mail.selected === uid) $("message-status").textContent = e.message;
    if (e.status === 409) $("message-status").textContent = `${e.message} (press R)`;
  }
}

function showMessage(detail) {
  const s = detail.summary;
  $("message-subject").textContent = s.subject;
  $("message-from").textContent = s.fromName;
  $("message-address").textContent = s.fromAddress && s.fromAddress !== s.fromName ? `<${s.fromAddress}>` : "";
  $("message-date").textContent = s.date ? longDate.format(new Date(s.date)) : "";
  const to = detail.to.length ? `To ${detail.to.join(", ")}` : "";
  const cc = detail.cc.length ? ` · Cc ${detail.cc.join(", ")}` : "";
  $("message-to").textContent = to + cc;
  const showImages = detail.imagesAllowed;
  $("images-bar").hidden = !(detail.remoteImages > 0 && !showImages);
  $("trust-sender").hidden = !s.fromAddress;
  $("attachments").replaceChildren(
    ...detail.attachments.map((a) => {
      const q = `folder=${encodeURIComponent(detail.folder)}&validity=${detail.validity}&uid=${s.uid}&path=${encodeURIComponent(a.path)}`;
      const link = el("a", { href: `/api/message/attachment?${q}`, download: a.filename, title: `Download ${a.filename}` },
        el("span", { textContent: `📎 ${a.filename}` }),
        el("small", { textContent: formatSize(a.size) }));
      return el("li", {}, link);
    }),
  );
  $("message-status").textContent = "";
  $("message-body").hidden = false;
  $("message-body").src = bodyUrl(detail, showImages);
}

function closeMessage() {
  mail.detail = null;
  $("app").classList.remove("reading");
  $("message").hidden = true;
  $("reader-empty").hidden = false;
  $("message-body").removeAttribute("src");
}

async function markSeen(uids, seen) {
  const changed = mail.messages.filter((m) => uids.includes(m.uid) && m.seen !== seen);
  if (!changed.length) return;
  for (const m of changed) {
    m.seen = seen;
    const item = $(`m-${m.uid}`);
    if (item) item.classList.toggle("unread", !seen);
  }
  const folder = mail.folders.find((f) => f.id === mail.folder);
  if (folder) {
    folder.unread = Math.max(0, folder.unread + (seen ? -changed.length : changed.length));
    renderFolders();
  }
  try {
    await api("POST", "/api/messages/seen", { folder: mail.folder, validity: mail.validity, uids: changed.map((m) => m.uid), seen });
  } catch (e) {
    $("list-status").textContent = e.message;
  }
}

function moveSelection(step) {
  if (!mail.messages.length) return;
  const index = mail.messages.findIndex((m) => m.uid === mail.selected);
  const next = index < 0 ? 0 : Math.min(mail.messages.length - 1, Math.max(0, index + step));
  if (index === next) return;
  selectMessage(mail.messages[next].uid);
}

function typing(event) {
  const t = event.target;
  return t instanceof HTMLInputElement || t instanceof HTMLTextAreaElement || t instanceof HTMLSelectElement || (t && t.isContentEditable);
}

function onKey(event) {
  if ($("app").hidden || $("settings").open || typing(event) || event.metaKey || event.ctrlKey || event.altKey) return;
  switch (event.key) {
    case "ArrowDown":
    case "j":
      moveSelection(1);
      break;
    case "ArrowUp":
    case "k":
      moveSelection(-1);
      break;
    case "Escape":
      if (mail.selected !== null) {
        mail.selected = null;
        markSelected();
        closeMessage();
        $("message-list").focus();
      }
      break;
    case "r":
    case "R":
      refresh();
      break;
    default:
      return;
  }
  event.preventDefault();
}

async function refresh() {
  await loadFolders().catch(() => {});
  if (mail.folder) await openFolder(mail.folder);
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
        showCodes(done.recoveryCodes, openMailbox);
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
        await openMailbox();
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
        await openMailbox();
      } catch (e) {
        setError(form, explain(e));
      }
    });
  });

  $("signout").addEventListener("click", async () => {
    await api("POST", "/api/signout").catch(() => {});
    closeMessage();
    $("message-list").replaceChildren();
    show("signin");
  });

  $("open-settings").addEventListener("click", async () => {
    await loadSettings().catch(() => {});
    $("settings").showModal();
  });
  $("close-settings").addEventListener("click", () => $("settings").close());

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
        await loadSettings();
      } catch (e) {
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
        showCodes(made.recoveryCodes, () => {
          show("app");
          loadSettings().then(() => $("settings").showModal()).catch(() => {});
        });
      } catch (e) {
        setError(card, explain(e));
      }
    });
  });

  $("folder-select").addEventListener("change", (event) => openFolder(event.target.value));
  $("refresh").addEventListener("click", refresh);
  $("load-older").addEventListener("click", loadOlder);
  $("back").addEventListener("click", () => {
    mail.selected = null;
    markSelected();
    closeMessage();
  });
  $("load-images").addEventListener("click", () => {
    if (!mail.detail) return;
    $("images-bar").hidden = true;
    $("message-body").src = bodyUrl(mail.detail, true);
  });
  $("trust-sender").addEventListener("click", async () => {
    const detail = mail.detail;
    if (!detail) return;
    await api("POST", "/api/image-senders", { address: detail.summary.fromAddress }).catch(() => {});
    detail.imagesAllowed = true;
    $("images-bar").hidden = true;
    $("message-body").src = bodyUrl(detail, true);
  });
  $("message-list").addEventListener("focus", () => {
    if (mail.selected === null && mail.messages.length) {
      mail.selected = mail.messages[0].uid;
      markSelected();
    }
  });
  $("message-list").addEventListener("keydown", (event) => {
    if (event.key === "Enter" && mail.selected !== null) {
      selectMessage(mail.selected);
      event.preventDefault();
    }
  });
  document.addEventListener("keydown", onKey);

  start();
});
