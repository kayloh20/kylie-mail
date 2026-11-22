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

/** [background] marks the page checking for mail by itself, which doesn't keep you signed in. */
async function api(method, path, body, { background = false } = {}) {
  const headers = body === undefined ? {} : { "Content-Type": "application/json" };
  if (background) headers["X-Background"] = "1";
  const response = await fetch(path, {
    method,
    credentials: "same-origin",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data.error || "Something went wrong. Try again.");
    error.status = response.status;
    // Signed out on the server (expired, or it restarted): back to the sign-in screen, unless
    // you're in the middle of writing; then Send says so and nothing is lost.
    if (response.status === 401 && !$("app").hidden && !(background && $("compose").open)) show("signin");
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

// Settings: smart filing

let trainingPoll = null;

async function loadFiling() {
  const filing = await api("GET", "/api/filing");
  const trainedFolders = filing.folders.filter((f) => f.trained);
  $("filing-summary").textContent = filing.examples
    ? `Learned from ${plural(filing.examples, "email")}${trainedFolders.length ? ` in ${plural(trainedFolders.length, "folder")}` : ""}, plus every move you make.`
    : "Not trained yet. Check the folders you file mail into, then start.";
  const running = filing.status.state === "running";
  if (!running || !$("filing-folders").children.length) {
    $("filing-folders").replaceChildren(
      ...filing.folders.map((f) => {
        const box = el("input", { type: "checkbox", value: f.id, checked: f.trained });
        return el("li", {}, el("label", {}, box, el("span", { textContent: f.name }), el("small", { textContent: f.examples ? plural(f.examples, "email") : "" })));
      }),
    );
  }
  for (const box of $("filing-folders").querySelectorAll("input")) box.disabled = running;
  $("train").disabled = running;
  $("stop-training").hidden = !running;
  const s = filing.status;
  $("training-status").textContent =
    s.state === "running" ? (s.folder ? `Learning from ${s.folder} (${s.index} of ${s.total}), ${plural(s.learned, "email")} so far…` : "Starting…")
    : s.state === "finished" ? `Done: learned from ${plural(s.learned, "new email")}.`
    : s.state === "failed" ? s.message || "Training stopped."
    : "";
  clearTimeout(trainingPoll);
  if (running && $("settings").open) trainingPoll = setTimeout(() => loadFiling().catch(() => {}), 1000);
  if (s.state === "finished") mail.suggest.key = ""; // new lessons: ask again
  return filing;
}

async function loadIdentity() {
  const identity = await api("GET", "/api/compose/identity");
  const form = $("identity-form");
  form.elements.name.value = identity.name;
  form.elements.signature.value = identity.signature;
  $("identity-saved").textContent = "";
}

async function openSettings(scrollTo) {
  await Promise.all([loadSettings().catch(() => {}), loadFiling().catch(() => {}), loadIdentity().catch(() => {})]);
  if (!$("settings").open) $("settings").showModal();
  if (scrollTo) $(scrollTo).scrollIntoView({ block: "start" });
}

// The mailbox

const mail = {
  folders: [],
  folder: null, // folder id
  validity: 0,
  messages: [],
  before: null,
  selected: new Set(), // uids
  anchor: null, // where Shift-selection starts
  cursor: null, // the message the arrow keys move from
  detail: null, // the open message
  loadToken: 0,
  busy: 0, // changes on their way to the server; checks for new mail wait for them
  lastRemoval: null, // the last delete or move, for Undo
  listId: "", // what the list shows: a folder or a search
  search: null, // { q, scope } while searching
  items: new Map(), // list rows by message key
  undo: null, // what Undo does right now
  suggest: { key: "", trained: true, list: [] }, // smart filing's ideas for the selection
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
  node.append(...children.filter((c) => c !== null && c !== undefined && c !== false));
  return node;
}

const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;
const isMac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
const mod = isMac ? "⌘" : "Ctrl+";

// Folders

function currentFolder() {
  return mail.folders.find((f) => f.id === mail.folder);
}

function folderWithRole(role) {
  return mail.folders.find((f) => f.role === role);
}

function folderById(id) {
  return mail.folders.find((f) => f.id === id);
}

function roleOf(folderId) {
  const folder = folderById(folderId);
  return folder ? folder.role : "custom";
}

/** Archive works when there's an Archive folder and none of the selection is already there. */
function canArchive() {
  const chosen = selectedMessages();
  return !!folderWithRole("archive") && chosen.length > 0 && chosen.every((m) => roleOf(m.folder) !== "archive");
}

/** Deleting from Trash, or when there's no Trash, deletes for good, as in the app. */
function deletesForGood() {
  const chosen = selectedMessages();
  return !folderWithRole("trash") || (chosen.length > 0 && chosen.every((m) => roleOf(m.folder) === "trash"));
}

async function openMailbox() {
  show("app");
  startChecking();
  loadSettings().catch(() => {});
  await loadFolders();
  const inbox = folderWithRole("inbox") || mail.folders[0];
  if (inbox) await openFolder(inbox.id);
}

async function loadFolders() {
  mail.folders = await api("GET", "/api/folders");
  renderFolders();
}

function renderFolders() {
  const inbox = folderWithRole("inbox");
  document.title = inbox && inbox.unread > 0 ? `(${inbox.unread}) Kylie Mail` : "Kylie Mail";
  const showing = mail.search ? null : mail.folder;
  $("folder-list").replaceChildren(
    ...mail.folders.map((f) => {
      const showCount = f.unread > 0 && !["sent", "drafts", "trash", "junk"].includes(f.role);
      const button = el(
        "button",
        { type: "button", title: f.name },
        el("span", { textContent: f.name }),
        showCount ? el("span", { className: "count", textContent: String(f.unread) }) : null,
      );
      button.style.paddingLeft = `${12 + f.depth * 14}px`;
      button.setAttribute("aria-current", String(f.id === showing));
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

function adjustCounts(folderId, unread, total) {
  const folder = folderById(folderId);
  if (!folder) return;
  folder.unread = Math.max(0, folder.unread + unread);
  folder.total = Math.max(0, folder.total + total);
}

/** Each message remembers its folder: search results come from several. */
function tag(folder, validity, message) {
  return Object.assign(message, { folder, validity, key: `${folder}\n${message.uid}` });
}

function resetList(listId) {
  mail.loadToken++;
  mail.listId = listId;
  mail.messages = [];
  mail.before = null;
  mail.selected.clear();
  mail.anchor = mail.cursor = null;
  syncReader();
  $("message-list").replaceChildren();
  $("load-older").hidden = true;
  return mail.loadToken;
}

async function openFolder(id) {
  mail.folder = id;
  endSearch();
  const token = resetList(`folder:${id}`);
  renderFolders();
  const folder = currentFolder();
  $("folder-title").textContent = folder ? folder.name : id;
  $("list-status").textContent = "Loading…";
  try {
    const page = await api("GET", `/api/messages?folder=${encodeURIComponent(id)}`);
    if (token !== mail.loadToken) return;
    mail.validity = page.validity;
    mail.messages = page.messages.map((m) => tag(page.folder, page.validity, m));
    mail.before = page.before;
    renderMessages();
  } catch (e) {
    if (token === mail.loadToken) $("list-status").textContent = e.message;
  }
}

async function loadOlder() {
  if (!mail.before || mail.search) return;
  const token = mail.loadToken;
  await busy($("load-older"), async () => {
    try {
      const page = await api("GET", `/api/messages?folder=${encodeURIComponent(mail.folder)}&before=${mail.before}`);
      if (token !== mail.loadToken) return;
      const known = new Set(mail.messages.map((m) => m.key));
      mail.messages.push(...page.messages.map((m) => tag(page.folder, page.validity, m)).filter((m) => !known.has(m.key)));
      mail.before = page.before;
      renderMessages();
    } catch (e) {
      $("list-status").textContent = e.message;
    }
  });
}

// Search: from:, to:, subject:, body: and "quoted phrases", as in the app. iCloud does the
// searching, so nothing about your mail is indexed on the server.

let searchTimer = null;

function searchWanted() {
  const q = $("search").value.trim();
  return q.length >= 2 || /[:"]/.test(q) ? q : "";
}

/** Searches a moment after typing stops, or right away on Enter. */
function scheduleSearch(now = false) {
  clearTimeout(searchTimer);
  const q = searchWanted();
  if (!q) {
    if (mail.search && !$("search").value.trim()) openFolder(mail.folder);
    return;
  }
  searchTimer = setTimeout(() => runSearch(q), now ? 0 : 400);
}

async function runSearch(q) {
  const all = $("search-all").checked;
  const scope = all ? null : mail.folder;
  const listId = `search:${scope || "*"}:${q}`;
  if (mail.search && mail.listId === listId) return;
  mail.search = { q, scope };
  const token = resetList(listId);
  renderFolders();
  $("search-scope").hidden = false;
  $("folder-title").textContent = all ? "Search" : `Search in ${currentFolder() ? currentFolder().name : mail.folder}`;
  $("list-status").textContent = "Searching iCloud…";
  try {
    const results = await api("GET", `/api/search?q=${encodeURIComponent(q)}${scope ? `&folder=${encodeURIComponent(scope)}` : ""}`);
    if (token !== mail.loadToken) return;
    mail.messages = results.hits.map((h) => tag(h.folder, h.validity, h.summary));
    renderMessages();
    const notes = [mail.messages.length ? plural(mail.messages.length, "result") : "Nothing found."];
    if (results.limited.length) notes.push(`Only the newest 50 in ${results.limited.join(", ")}.`);
    for (const skipped of results.skipped) notes.push(`Couldn't search ${skipped.name}: ${skipped.reason}.`);
    $("list-status").textContent = notes.join(" ");
  } catch (e) {
    if (token === mail.loadToken && e.status !== 409) $("list-status").textContent = e.message;
  }
}

function endSearch() {
  clearTimeout(searchTimer);
  mail.search = null;
  $("search").value = "";
  $("search-scope").hidden = true;
}

function focusSearch() {
  if ($("app").classList.contains("reading") && window.matchMedia("(max-width: 1000px)").matches) clearSelection();
  $("search").focus();
  $("search").select();
}

// The message list

function renderMessages({ scroll = true } = {}) {
  if (!mail.search) $("list-status").textContent = mail.messages.length ? "" : "No messages here.";
  const showFolders = !!mail.search && !mail.search.scope;
  mail.items = new Map();
  $("message-list").replaceChildren(
    ...mail.messages.map((m) => {
      const marks = [m.flagged ? "⚑" : "", m.hasAttachments ? "📎" : "", m.answered ? "↩" : ""].join(" ").trim();
      const folder = showFolders ? folderById(m.folder) : null;
      const item = el(
        "li",
        { className: m.seen ? "" : "unread" },
        el("span", { className: "from", textContent: m.fromName || m.fromAddress || "(unknown sender)" }),
        el("span", { className: "date", textContent: listDate(m.date) }),
        el("span", { className: "subject", textContent: m.subject },
          marks ? el("span", { className: "marks", textContent: ` ${marks}` }) : null,
          folder ? el("span", { className: "folder-tag", textContent: folder.name }) : null),
      );
      item.dataset.key = m.key;
      item.setAttribute("role", "option");
      mail.items.set(m.key, item);
      return item;
    }),
  );
  $("load-older").hidden = !mail.before || !!mail.search;
  markSelected(scroll);
}

function markSelected(scroll = true) {
  for (const item of $("message-list").children) {
    const key = item.dataset.key;
    item.setAttribute("aria-selected", String(mail.selected.has(key)));
    item.classList.toggle("cursor", key === mail.cursor);
  }
  const current = scroll && mail.items && mail.items.get(mail.cursor);
  if (current) current.scrollIntoView({ block: "nearest" });
}

function indexOf(key) {
  return mail.messages.findIndex((m) => m.key === key);
}

function messageByKey(key) {
  return mail.messages.find((m) => m.key === key);
}

function selectOnly(key) {
  mail.selected = new Set([key]);
  mail.anchor = mail.cursor = key;
  markSelected();
  syncReader();
}

function toggleSelected(key) {
  if (mail.selected.has(key)) mail.selected.delete(key);
  else mail.selected.add(key);
  mail.anchor = mail.cursor = key;
  markSelected();
  syncReader();
}

function selectRangeTo(key) {
  const from = indexOf(mail.anchor ?? key);
  const to = indexOf(key);
  const [a, b] = from < to ? [from, to] : [to, from];
  mail.selected = new Set(mail.messages.slice(Math.max(a, 0), b + 1).map((m) => m.key));
  mail.cursor = key;
  markSelected();
  syncReader();
}

function selectAll() {
  if (!mail.messages.length) return;
  mail.selected = new Set(mail.messages.map((m) => m.key));
  mail.anchor = mail.messages[0].key;
  mail.cursor = mail.cursor ?? mail.anchor;
  markSelected();
  syncReader();
}

function clearSelection() {
  mail.selected.clear();
  mail.anchor = null;
  markSelected();
  syncReader();
}

function step(by, extend) {
  if (!mail.messages.length) return;
  const index = indexOf(mail.cursor);
  const next = index < 0 ? 0 : Math.min(mail.messages.length - 1, Math.max(0, index + by));
  const key = mail.messages[next].key;
  if (extend) {
    if (mail.anchor === null) mail.anchor = mail.cursor ?? key;
    selectRangeTo(key);
  } else if (index !== next || !mail.selected.has(key) || mail.selected.size !== 1) {
    selectOnly(key);
  }
}

function onListClick(event) {
  const item = event.target.closest("li[data-key]");
  if (!item) return;
  const key = item.dataset.key;
  if (event.shiftKey) selectRangeTo(key);
  else if (event.metaKey || event.ctrlKey) toggleSelected(key);
  else selectOnly(key);
}

// The reader: one message, several selected, or nothing

function syncReader() {
  const count = mail.selected.size;
  $("app").classList.toggle("reading", count > 0);
  $("toolbar").hidden = count === 0;
  $("many").hidden = count < 2;
  if (count === 0) {
    closeMessage();
    return;
  }
  updateToolbar();
  requestSuggestions();
  if (count > 1) {
    mail.detail = null;
    $("reader-empty").hidden = true;
    $("message").hidden = true;
    $("message-body").removeAttribute("src");
    $("many-count").textContent = `${count} messages selected`;
    return;
  }
  const key = [...mail.selected][0];
  const open = mail.detail && `${mail.detail.folder}\n${mail.detail.summary.uid}` === key;
  if (!open || $("message").hidden) openMessage(key);
}

function selectedMessages() {
  return mail.messages.filter((m) => mail.selected.has(m.key));
}

/** The folder all the selected messages are in, or null when they're from several. */
function selectionFolder() {
  const folders = new Set(selectedMessages().map((m) => m.folder));
  return folders.size === 1 ? [...folders][0] : null;
}

function updateToolbar() {
  const chosen = selectedMessages();
  const anyUnread = chosen.some((m) => !m.seen);
  const allFlagged = chosen.length > 0 && chosen.every((m) => m.flagged);
  const buttons = Object.fromEntries([...$("toolbar").querySelectorAll("[data-act]")].map((b) => [b.dataset.act, b]));
  buttons.read.textContent = anyUnread ? "Mark as read" : "Mark as unread";
  buttons.flag.textContent = allFlagged ? "Unflag" : "Flag";
  buttons.archive.hidden = !canArchive();
  buttons.delete.textContent = deletesForGood() ? "Delete for good" : "Delete";
  const single = chosen.length === 1;
  const drafts = inDrafts();
  for (const name of ["reply", "replyAll", "forward"]) buttons[name].hidden = !single || drafts;
  buttons.editDraft.hidden = !single || !drafts;
}

/** Whether the one selected message is a draft. */
function inDrafts() {
  const chosen = selectedMessages();
  return chosen.length === 1 && roleOf(chosen[0].folder) === "drafts";
}

/** The one selected message, as the server knows it. */
function selectedRef() {
  const chosen = selectedMessages();
  if (chosen.length !== 1) return null;
  return { folder: chosen[0].folder, validity: chosen[0].validity, uid: chosen[0].uid };
}

function bodyUrl(detail, images) {
  const q = `folder=${encodeURIComponent(detail.folder)}&validity=${detail.validity}&uid=${detail.summary.uid}`;
  return `/api/message/body?${q}${images ? "&images=1" : ""}`;
}

async function openMessage(key) {
  const summary = messageByKey(key);
  if (!summary) return;
  $("reader-empty").hidden = true;
  $("message").hidden = false;
  mail.detail = null;
  $("message-subject").textContent = summary.subject;
  $("message-from").textContent = summary.fromName;
  $("message-address").textContent = summary.fromAddress !== summary.fromName ? `<${summary.fromAddress}>` : "";
  $("message-date").textContent = summary.date ? longDate.format(new Date(summary.date)) : "";
  $("message-to").textContent = "";
  $("images-bar").hidden = true;
  $("attachments").replaceChildren();
  $("message-status").textContent = "Loading…";
  $("message-body").hidden = true;
  $("message-body").removeAttribute("src");
  const stillOpen = () => mail.selected.size === 1 && mail.selected.has(key);
  try {
    const detail = await api("GET", `/api/message?folder=${encodeURIComponent(summary.folder)}&validity=${summary.validity}&uid=${summary.uid}`);
    if (!stillOpen()) return;
    mail.detail = detail;
    showMessage(detail);
    if (!summary.seen) setFlag([key], "seen", true);
  } catch (e) {
    if (stillOpen()) $("message-status").textContent = e.status === 409 ? `${e.message} (refresh with ↻)` : e.message;
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
  $("images-bar").hidden = !(detail.remoteImages > 0 && !detail.imagesAllowed);
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
  $("message-body").src = bodyUrl(detail, detail.imagesAllowed);
}

function closeMessage() {
  mail.detail = null;
  $("suggest-bar").hidden = true;
  $("app").classList.remove("reading");
  $("message").hidden = true;
  $("many").hidden = true;
  $("toolbar").hidden = true;
  $("reader-empty").hidden = false;
  $("message-body").removeAttribute("src");
}

// Smart filing suggestions

function selectionKey() {
  return [...mail.selected].sort().join("|");
}

let suggestTimer = null;

/** Asks where the selected mail might go, a moment after the selection settles. */
function requestSuggestions() {
  const key = selectionKey();
  if (mail.suggest.key === key) return renderSuggestBar();
  $("suggest-bar").hidden = true;
  clearTimeout(suggestTimer);
  const folder = selectionFolder();
  // Suggestions are for messages from one folder at a time.
  if (!folder) return;
  suggestTimer = setTimeout(async () => {
    if (selectionKey() !== key || !mail.selected.size) return;
    try {
      const chosen = selectedMessages();
      const uids = chosen.slice(0, 50).map((m) => m.uid).join(",");
      const reply = await api("GET", `/api/filing/suggest?folder=${encodeURIComponent(folder)}&validity=${chosen[0].validity}&uids=${uids}`);
      mail.suggest = { key, trained: reply.trained, list: reply.suggestions };
      if (selectionKey() === key) renderSuggestBar();
    } catch {
      // Suggestions are a nicety; the mail still works without them.
    }
  }, 150);
}

function currentSuggestions() {
  return mail.suggest.key === selectionKey() ? mail.suggest.list : [];
}

function renderSuggestBar() {
  const bar = $("suggest-bar");
  if (mail.suggest.key !== selectionKey() || !mail.selected.size) {
    bar.hidden = true;
    return;
  }
  if (!mail.suggest.trained) {
    const train = el("button", { type: "button", className: "link", textContent: "Train smart filing" });
    train.onclick = () => openSettings("filing-card");
    bar.replaceChildren(el("span", { className: "spark", textContent: "✨" }), el("span", { textContent: "Smart filing can suggest folders once it learns how you file." }), train);
    bar.hidden = false;
    return;
  }
  const confident = mail.suggest.list.filter((s) => s.confident).slice(0, 2);
  if (!confident.length) {
    bar.hidden = true;
    return;
  }
  bar.replaceChildren(
    el("span", { className: "spark", textContent: "✨" }),
    el("span", { textContent: "File to" }),
    ...confident.map((s) => {
      const chip = el("button", { type: "button", className: "chip", textContent: s.name, title: s.reason });
      chip.setAttribute("aria-label", `File to ${s.name}: ${s.reason}`);
      chip.onclick = () => removeSelected("move", s.folder);
      return chip;
    }),
  );
  bar.hidden = false;
}

// Acting on mail

function act(action) {
  const keys = [...mail.selected];
  if (!keys.length) return;
  const chosen = selectedMessages();
  switch (action) {
    case "read":
      return setFlag(keys, "seen", chosen.some((m) => !m.seen));
    case "flag":
      return setFlag(keys, "flagged", !chosen.every((m) => m.flagged));
    case "archive":
      if (canArchive()) removeSelected("archive");
      return;
    case "delete":
      return removeSelected("delete");
    case "move":
      return openPicker("move");
    case "reply":
    case "replyAll":
    case "forward":
      if (!inDrafts() && selectedRef()) openCompose(action, selectedRef());
      return;
    case "editDraft":
      if (inDrafts() && selectedRef()) openCompose("draft", selectedRef());
      return;
  }
}

/** Groups messages by the folder they're in: the server acts on one folder at a time. */
function byFolder(messages) {
  const groups = new Map();
  for (const m of messages) {
    const id = `${m.folder}\n${m.validity}`;
    if (!groups.has(id)) groups.set(id, { folder: m.folder, validity: m.validity, messages: [] });
    groups.get(id).messages.push(m);
  }
  return [...groups.values()];
}

/** Changes read or flagged right away, on screen first. */
async function setFlag(keys, flag, value) {
  const field = flag === "seen" ? "seen" : "flagged";
  const changed = mail.messages.filter((m) => keys.includes(m.key) && m[field] !== value);
  if (!changed.length) return;
  const apply = (v) => {
    for (const m of changed) {
      m[field] = v;
      if (flag === "seen") adjustCounts(m.folder, v ? -1 : 1, 0);
    }
    renderFolders();
    renderMessages();
    updateToolbar();
  };
  apply(value);
  mail.busy++;
  try {
    for (const group of byFolder(changed)) {
      await api("POST", "/api/messages/flags", { folder: group.folder, validity: group.validity, uids: group.messages.map((m) => m.uid), flag, value });
    }
  } catch (e) {
    apply(!value);
    toast(e.message);
  } finally {
    mail.busy--;
  }
}

/**
 * Deletes, archives or moves the selected messages. They leave the list at once and the next
 * message opens; the server waits a few seconds before telling iCloud, so Undo can take it back.
 * Search results can come from several folders: each folder gets its own request, and Undo takes
 * them all back.
 */
async function removeSelected(action, target = null) {
  const listId = mail.listId;
  const items = mail.messages.map((m, index) => ({ m, index })).filter(({ m }) => mail.selected.has(m.key));
  if (!items.length) return;
  const wasOpen = mail.selected.size === 1;
  const lastIndex = items[items.length - 1].index;
  const next = mail.messages.slice(lastIndex + 1).find((m) => !mail.selected.has(m.key)) ||
    mail.messages.slice(0, items[0].index).reverse().find((m) => !mail.selected.has(m.key));

  mail.messages = mail.messages.filter((m) => !mail.selected.has(m.key));
  mail.selected.clear();
  mail.anchor = null;
  mail.cursor = next ? next.key : null;
  renderMessages();
  if (next && wasOpen) selectOnly(next.key);
  else syncReader();

  const done = [];
  const failed = [];
  let error = null;
  mail.busy++;
  for (const group of byFolder(items.map(({ m }) => m))) {
    const groupItems = items.filter(({ m }) => m.folder === group.folder && m.validity === group.validity);
    const unseen = groupItems.filter(({ m }) => !m.seen).length;
    try {
      const removal = await api("POST", "/api/messages/remove", {
        folder: group.folder, validity: group.validity, uids: group.messages.map((m) => m.uid), action, target,
      });
      adjustCounts(group.folder, -unseen, -groupItems.length);
      if (removal.target) adjustCounts(removal.target, unseen, groupItems.length);
      done.push({ removal, items: groupItems, unseen });
    } catch (e) {
      failed.push(...groupItems);
      error = e;
    }
  }
  mail.busy--;
  renderFolders();
  // Put back what didn't happen on the server.
  if (failed.length && mail.listId === listId) restore(failed);
  if (!done.length) return toast(error ? error.message : "Nothing changed.");

  mail.lastRemoval = { listId, parts: done };
  mail.undo = undo;
  for (const { removal } of done) {
    // Once the change has reached iCloud, show it in the folder it went to, if that's open by then.
    if (removal.target) {
      setTimeout(() => {
        if (!mail.search && mail.folder === removal.target) softRefresh();
      }, removal.undoMs + 1500);
    }
  }
  const count = done.reduce((n, d) => n + d.items.length, 0);
  const what = plural(count, "message");
  const first = done[0].removal;
  const text = done.every((d) => !d.removal.target)
    ? `Deleted ${what} for good`
    : `Moved ${what} to ${first.targetName || "another folder"}`;
  toast(error ? `${text}. ${error.message}` : text, first.undoMs);
}

function restore(items) {
  for (const { m, index } of [...items].sort((a, b) => a.index - b.index)) {
    if (indexOf(m.key) < 0) mail.messages.splice(Math.min(index, mail.messages.length), 0, m);
  }
  mail.selected = new Set(items.map(({ m }) => m.key));
  mail.anchor = mail.cursor = items[0].m.key;
  renderMessages();
  syncReader();
}

async function undo() {
  const last = mail.lastRemoval;
  if (!last) return;
  mail.lastRemoval = null;
  hideToast();
  const back = [];
  let late = false;
  for (const { removal, items, unseen } of last.parts) {
    try {
      const result = await api("POST", "/api/messages/undo", { id: removal.id });
      if (!result.undone) {
        late = true;
        continue;
      }
      adjustCounts(items[0].m.folder, unseen, items.length);
      if (removal.target) adjustCounts(removal.target, -unseen, -items.length);
      back.push(...items);
    } catch (e) {
      late = true;
    }
  }
  renderFolders();
  if (back.length && mail.listId === last.listId) restore(back);
  toast(late ? (back.length ? "Partly undone: some had already gone." : "Too late to undo: it's already done.") : "Undone");
}

// Toasts

let toastTimer = null;

let toastAction = null;

/**
 * A short notice at the bottom. [undoMs] offers Undo (for the last delete, move or send) for that
 * long; [action] offers some other button instead, such as Open for a message that failed to send.
 */
function toast(text, undoMs = 0, action = null) {
  $("toast-text").textContent = text;
  toastAction = undoMs ? { label: "Undo", run: runUndo } : action;
  $("toast-undo").textContent = toastAction ? toastAction.label : "";
  $("toast-undo").hidden = !toastAction;
  $("toast").hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    hideToast();
    if (undoMs) mail.undo = null;
  }, undoMs || (action ? 8000 : 3500));
}

/** Ctrl/Cmd+Z and the notice's Undo: takes back the last delete, move or send, while it still can. */
function runUndo() {
  const undo = mail.undo;
  mail.undo = null;
  if (undo) undo();
}

function hideToast() {
  clearTimeout(toastTimer);
  $("toast").hidden = true;
}

// The right-click menu

function openMenu(x, y) {
  const chosen = selectedMessages();
  if (!chosen.length) return;
  const anyUnread = chosen.some((m) => !m.seen);
  const allFlagged = chosen.every((m) => m.flagged);
  const item = (label, keys, action) => {
    const button = el("button", { type: "button" }, el("span", { textContent: label }), keys ? el("kbd", { textContent: keys }) : null);
    button.setAttribute("role", "menuitem");
    button.onclick = () => {
      closeMenu();
      act(action);
    };
    return button;
  };
  const best = currentSuggestions().find((s) => s.confident);
  let fileTo = null;
  if (best) {
    fileTo = el("button", { type: "button", title: best.reason }, el("span", {}, el("span", { className: "spark", textContent: "✨ " }), document.createTextNode(`File to ${best.name}`)));
    fileTo.setAttribute("role", "menuitem");
    fileTo.onclick = () => {
      closeMenu();
      removeSelected("move", best.folder);
    };
  }
  const single = chosen.length === 1;
  const writing = single
    ? (inDrafts() ? [item("Edit draft", "↵", "editDraft"), el("hr")]
      : [item("Reply", "R", "reply"), item("Reply all", "A", "replyAll"), item("Forward", "F", "forward"), el("hr")])
    : [];
  const menu = $("menu");
  menu.replaceChildren(
    ...writing,
    fileTo,
    fileTo ? el("hr") : null,
    item(anyUnread ? "Mark as read" : "Mark as unread", "U", "read"),
    item(allFlagged ? "Unflag" : "Flag", "S", "flag"),
    el("hr"),
    canArchive() ? item("Archive", "E", "archive") : null,
    item("Move to…", "M", "move"),
    item(deletesForGood() ? "Delete for good" : "Delete", isMac ? "⌫" : "Del", "delete"),
  );
  menu.hidden = false;
  const rect = menu.getBoundingClientRect();
  menu.style.left = `${Math.min(x, window.innerWidth - rect.width - 8)}px`;
  menu.style.top = `${Math.min(y, window.innerHeight - rect.height - 8)}px`;
  menu.querySelector("button").focus();
}

function closeMenu() {
  $("menu").hidden = true;
}

function onMenuKey(event) {
  const buttons = [...$("menu").querySelectorAll("button")];
  const index = buttons.indexOf(document.activeElement);
  if (event.key === "ArrowDown") buttons[(index + 1) % buttons.length].focus();
  else if (event.key === "ArrowUp") buttons[(index - 1 + buttons.length) % buttons.length].focus();
  else if (event.key === "Escape" || event.key === "Tab") {
    closeMenu();
    $("message-list").focus();
  } else return;
  event.preventDefault();
  event.stopPropagation();
}

// The folder picker: Move to… and Go to folder

const picker = { mode: "move", options: [], highlight: 0 };

function openPicker(mode) {
  if (mode === "move" && !mail.selected.size) return;
  picker.mode = mode;
  $("picker-title").textContent = mode === "move" ? `Move ${plural(mail.selected.size, "message")} to…` : "Go to folder";
  $("picker-filter").value = "";
  picker.highlight = 0;
  renderPicker();
  $("picker").showModal();
  $("picker-filter").focus();
}

function renderPicker() {
  const query = $("picker-filter").value.trim().toLowerCase();
  const from = selectionFolder();
  const folders = mail.folders.filter((f) => (picker.mode !== "move" || f.id !== from) && f.name.toLowerCase().includes(query));
  // Moving: smart filing's best guesses first, with why, as in the app.
  const suggested = picker.mode === "move" && !query
    ? currentSuggestions().map((s) => ({ ...mail.folders.find((f) => f.id === s.folder), reason: s.reason })).filter((f) => f.id)
    : [];
  picker.options = [...suggested, ...folders];
  picker.highlight = query ? 0 : Math.min(picker.highlight, Math.max(0, picker.options.length - 1));
  const row = (f, i) => {
    const li = el("li", {}, el("span", { textContent: f.name }), f.reason ? el("small", { textContent: f.reason }) : null);
    li.setAttribute("role", "option");
    li.setAttribute("aria-selected", String(i === picker.highlight));
    li.onclick = () => pick(f);
    return li;
  };
  const section = (text) => {
    const li = el("li", { className: "section", textContent: text });
    li.setAttribute("role", "presentation");
    return li;
  };
  const rows = picker.options.map(row);
  $("picker-list").replaceChildren(
    ...(suggested.length ? [section("Suggested"), ...rows.slice(0, suggested.length), section("All folders")] : []),
    ...rows.slice(suggested.length),
  );
  const current = rows[picker.highlight];
  if (current) current.scrollIntoView({ block: "nearest" });
}

function pick(folder) {
  $("picker").close();
  if (!folder) return;
  if (picker.mode === "move") removeSelected("move", folder.id);
  else openFolder(folder.id);
}

function onPickerKey(event) {
  if (event.key === "ArrowDown" || event.key === "ArrowUp") {
    const by = event.key === "ArrowDown" ? 1 : -1;
    picker.highlight = Math.min(picker.options.length - 1, Math.max(0, picker.highlight + by));
    renderPicker();
    event.preventDefault();
  } else if (event.key === "Enter") {
    pick(picker.options[picker.highlight]);
    event.preventDefault();
  }
}

// Writing

const compose = {
  draft: null, // what the server started with: reply headers, the draft it replaces, …
  files: [], // UploadInfo of attached files
  initialBody: "",
  contacts: null,
  suggest: { field: null, list: [], highlight: 0 },
  handled: false, // set when Send, Save or Discard closes the window on purpose
};

/** Closes the compose window on purpose (sent, saved or discarded). */
function finishCompose() {
  compose.handled = true;
  $("compose").close();
}

const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;

/** Opens the compose window: "new", "reply", "replyAll", "forward", or "draft" to edit one. */
async function openCompose(mode, message = null, restore = null) {
  closeMenu();
  try {
    const started = restore || (await api("POST", "/api/compose/start", { mode, message, zone }));
    compose.draft = started.draft;
    compose.files = started.attachments;
    compose.initialBody = restore ? "" : started.draft.body;
    const form = $("compose-form");
    form.elements.to.value = started.draft.to;
    form.elements.cc.value = started.draft.cc;
    form.elements.bcc.value = started.draft.bcc;
    form.elements.subject.value = started.draft.subject;
    form.elements.body.value = started.draft.body;
    $("cc-row").hidden = !started.draft.cc && !started.draft.bcc;
    $("bcc-row").hidden = $("cc-row").hidden;
    $("show-cc").hidden = !$("cc-row").hidden;
    const titles = { new: "New message", reply: "Reply", replyAll: "Reply all", forward: "Forward", draft: "Draft" };
    $("compose-title").textContent = titles[mode] || "New message";
    setError(form, started.warning || "");
    renderFiles();
    compose.handled = false;
    $("compose").showModal();
    // Replies start above the quote; new mail and forwards start with the address.
    if (form.elements.to.value && mode !== "forward") {
      form.elements.body.focus();
      form.elements.body.setSelectionRange(0, 0);
      form.elements.body.scrollTop = 0;
    } else {
      form.elements.to.focus();
    }
    loadContacts();
  } catch (e) {
    toast(e.message);
  }
}

function currentDraft() {
  const form = $("compose-form");
  return {
    ...compose.draft,
    to: form.elements.to.value,
    cc: form.elements.cc.value,
    bcc: form.elements.bcc.value,
    subject: form.elements.subject.value,
    body: form.elements.body.value,
    attachments: compose.files.map((f) => f.id),
  };
}

function isEmptyDraft(d) {
  return !d.to.trim() && !d.cc.trim() && !d.bcc.trim() && !d.subject.trim() &&
    d.body.trim() === compose.initialBody.trim() && !d.attachments.length && !compose.draft.replaces;
}

async function sendCompose() {
  const form = $("compose-form");
  const draft = currentDraft();
  setError(form, "");
  try {
    const sending = await api("POST", "/api/compose/send", draft);
    const files = [...compose.files];
    finishCompose();
    mail.undo = async () => {
      const result = await api("POST", "/api/compose/undo", { id: sending.id }).catch(() => ({ undone: false }));
      if (!result.undone) return toast("Too late to undo: it's on its way.");
      openCompose(null, null, { draft, attachments: files });
      $("compose-title").textContent = "Not sent";
    };
    toast("Sending…", sending.undoMs);
    watchSend(sending.id, draft, files);
  } catch (e) {
    setError(form, e.message);
  }
}

/** Follows a send until it has left or failed, and says so. */
function watchSend(id, draft, files) {
  const check = async () => {
    let status;
    try {
      status = (await api("GET", "/api/compose/status")).find((s) => s.id === id);
    } catch {
      return setTimeout(check, 3000);
    }
    if (!status) return; // undone
    if (status.state === "sent") {
      if ($("toast-text").textContent === "Sending…" || $("toast").hidden) toast("Sent");
      return;
    }
    if (status.state === "failed") {
      return toast(`Couldn't send “${draft.subject || "(no subject)"}”: ${status.error}`, 0, {
        label: "Open",
        run: () => openCompose(null, null, { draft, attachments: files }),
      });
    }
    setTimeout(check, 1500);
  };
  setTimeout(check, 1500);
}

/** Esc and ✕: an empty message just closes; anything else is saved to Drafts, as in the app. */
async function closeCompose() {
  const form = $("compose-form");
  const draft = currentDraft();
  if (isEmptyDraft(draft)) {
    finishCompose();
    api("POST", "/api/compose/discard", draft).catch(() => {});
    return;
  }
  try {
    await api("POST", "/api/compose/save", draft);
    finishCompose();
    toast("Saved to Drafts");
    const current = currentFolder();
    if (current && current.role === "drafts") softRefresh();
  } catch (e) {
    setError(form, `Couldn't save the draft: ${e.message}`);
  }
}

async function discardCompose() {
  const draft = currentDraft();
  if (!isEmptyDraft(draft) && !confirm("Discard this message?")) return;
  finishCompose();
  api("POST", "/api/compose/discard", draft).catch(() => {});
}

function renderFiles() {
  $("c-files").replaceChildren(
    ...compose.files.map((f) => {
      const remove = el("button", { type: "button", textContent: "✕", title: `Remove ${f.filename}` });
      remove.setAttribute("aria-label", `Remove ${f.filename}`);
      remove.onclick = () => {
        compose.files = compose.files.filter((x) => x.id !== f.id);
        api("POST", "/api/compose/upload/remove", { id: f.id }).catch(() => {});
        renderFiles();
      };
      return el("li", {}, el("a", {}, el("span", { textContent: `📎 ${f.filename}` }), el("small", { textContent: formatSize(f.size) })), remove);
    }),
  );
}

async function attachFiles(fileList) {
  const form = $("compose-form");
  for (const file of fileList) {
    try {
      const response = await fetch("/api/compose/upload", {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": file.type || "application/octet-stream", "X-Filename": encodeURIComponent(file.name) },
        body: file,
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.error || "Couldn't attach that file.");
      compose.files.push(data);
      renderFiles();
    } catch (e) {
      setError(form, `${file.name}: ${e.message}`);
    }
  }
}

// Address suggestions while writing

async function loadContacts() {
  if (compose.contacts) return;
  compose.contacts = [];
  compose.contacts = await api("GET", "/api/compose/contacts").catch(() => []);
}

function suggestFor(input) {
  const token = input.value.slice(0, input.selectionStart).split(/[,;]/).pop().trim().toLowerCase();
  const box = $("contact-suggest");
  if (token.length < 2 || token.includes(">") || !compose.contacts) return hideSuggest();
  const list = compose.contacts.filter((c) => c.address.includes(token) || c.name.toLowerCase().includes(token)).slice(0, 6);
  if (!list.length) return hideSuggest();
  compose.suggest = { field: input, list, highlight: 0 };
  box.replaceChildren(
    ...list.map((c, i) => {
      const li = el("li", {}, el("span", { textContent: c.name || c.address }), c.name ? el("small", { textContent: c.address }) : null);
      li.setAttribute("role", "option");
      li.setAttribute("aria-selected", String(i === 0));
      li.onmousedown = (event) => {
        event.preventDefault();
        acceptSuggestion(c);
      };
      return li;
    }),
  );
  const dialog = $("compose").getBoundingClientRect();
  const rect = input.getBoundingClientRect();
  box.style.left = `${rect.left - dialog.left}px`;
  box.style.top = `${rect.bottom - dialog.top + 4}px`;
  box.hidden = false;
}

function hideSuggest() {
  $("contact-suggest").hidden = true;
  compose.suggest.field = null;
}

function acceptSuggestion(contact) {
  const input = compose.suggest.field;
  if (!input) return;
  const before = input.value.slice(0, input.selectionStart);
  const after = input.value.slice(input.selectionStart);
  const start = Math.max(before.lastIndexOf(","), before.lastIndexOf(";")) + 1;
  const prefix = before.slice(0, start).trimEnd();
  const value = `${prefix ? `${prefix} ` : ""}${contact.address}, `;
  input.value = value + after.replace(/^[^,;]*[,;]?\s*/, "");
  input.setSelectionRange(value.length, value.length);
  hideSuggest();
}

function onAddressKey(event) {
  const box = $("contact-suggest");
  if (box.hidden || compose.suggest.field !== event.target) return;
  const { list } = compose.suggest;
  if (event.key === "ArrowDown" || event.key === "ArrowUp") {
    const by = event.key === "ArrowDown" ? 1 : -1;
    compose.suggest.highlight = (compose.suggest.highlight + by + list.length) % list.length;
    [...box.children].forEach((li, i) => li.setAttribute("aria-selected", String(i === compose.suggest.highlight)));
  } else if (event.key === "Enter" || event.key === "Tab") {
    acceptSuggestion(list[compose.suggest.highlight]);
  } else if (event.key === "Escape") {
    hideSuggest();
  } else {
    return;
  }
  event.preventDefault();
  event.stopPropagation();
}

// Keyboard

function typing(event) {
  const t = event.target;
  return t instanceof HTMLInputElement || t instanceof HTMLTextAreaElement || t instanceof HTMLSelectElement || (t && t.isContentEditable);
}

function onKey(event) {
  if ($("app").hidden || $("settings").open || $("picker").open || $("compose").open || !$("menu").hidden || typing(event) || event.altKey) return;
  const command = event.metaKey || event.ctrlKey;
  const key = event.key.length === 1 ? event.key.toLowerCase() : event.key;
  if (command) {
    if (key === "f" && !event.shiftKey) focusSearch();
    else if (key === "a") selectAll();
    else if (key === "z" && !event.shiftKey) runUndo();
    else if (key === "m" && event.shiftKey) act("move");
    else if (key === "k") openPicker("goto");
    else return;
    event.preventDefault();
    return;
  }
  switch (key) {
    case "ArrowDown":
    case "j":
      step(1, event.shiftKey);
      break;
    case "ArrowUp":
    case "k":
      step(-1, event.shiftKey);
      break;
    case "Escape":
      if (!mail.selected.size) return;
      clearSelection();
      $("message-list").focus();
      break;
    case "Delete":
    case "Backspace":
      act("delete");
      break;
    case "e":
      act("archive");
      break;
    case "m":
      act("move");
      break;
    case "u":
      act("read");
      break;
    case "s":
      act("flag");
      break;
    case "/":
      focusSearch();
      break;
    case "n":
      openCompose("new");
      break;
    case "r":
      act("reply");
      break;
    case "a":
      act("replyAll");
      break;
    case "f":
      act("forward");
      break;
    default:
      return;
  }
  event.preventDefault();
}

/** Reloads the newest messages without disturbing the selection. */
/**
 * Reloads the newest messages without disturbing the selection, the open message or where the
 * list is scrolled to: new mail appears at the top, read and flagged update, and mail removed
 * elsewhere disappears.
 */
async function softRefresh({ background = false } = {}) {
  const token = mail.loadToken;
  try {
    if (mail.search) return;
    const page = await api("GET", `/api/messages?folder=${encodeURIComponent(mail.folder)}`, undefined, { background });
    if (token !== mail.loadToken || mail.busy) return;
    const fresh = page.messages.map((m) => tag(page.folder, page.validity, m));
    const newest = new Set(fresh.map((m) => m.key));
    const oldestShown = Math.min(...fresh.map((m) => m.uid));
    const older = mail.before
      ? mail.messages.filter((m) => !newest.has(m.key) && m.validity === page.validity && m.uid < oldestShown)
      : [];
    const known = new Set(mail.messages.map((m) => m.key));
    const arrived = fresh.filter((m) => !known.has(m.key)).map((m) => m.key);
    mail.validity = page.validity;
    mail.messages = [...fresh, ...older];
    if (!mail.before) mail.before = page.before;
    const present = new Set(mail.messages.map((m) => m.key));
    const before = mail.selected.size;
    mail.selected = new Set([...mail.selected].filter((key) => present.has(key)));
    if (mail.cursor !== null && !present.has(mail.cursor)) mail.cursor = null;
    const list = $("message-list");
    const top = list.scrollTop;
    renderMessages({ scroll: false });
    // Keep what you were looking at in place when new rows arrive above it.
    if (top > 0) list.scrollTop = top + arrived.reduce((h, key) => h + (mail.items.get(key)?.offsetHeight || 0), 0);
    if (mail.selected.size !== before) syncReader();
    else updateToolbar();
  } catch {
    // Not worth interrupting for: ↻ reloads.
  }
}

// Checking for new mail

const CHECK_EVERY_MS = 60_000;
let lastCheck = 0;
let checking = false;
let checkTimer = null;

/**
 * Looks for new mail once a minute while the tab is visible, and as soon as you come back to it.
 * These checks don't count as using the page, so an open tab still signs out after 2 idle hours.
 */
async function checkForMail() {
  if (checking || $("app").hidden || document.visibilityState !== "visible" || mail.busy) return;
  checking = true;
  lastCheck = Date.now();
  try {
    mail.folders = await api("GET", "/api/folders", undefined, { background: true });
    renderFolders();
    if (!mail.search && mail.folder) await softRefresh({ background: true });
  } catch {
    // The next check will try again.
  } finally {
    checking = false;
  }
}

function startChecking() {
  clearInterval(checkTimer);
  lastCheck = Date.now();
  checkTimer = setInterval(checkForMail, CHECK_EVERY_MS);
}

async function refresh() {
  await loadFolders().catch(() => {});
  if (mail.search) {
    const q = mail.search.q;
    mail.listId = "";
    return runSearch(q);
  }
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

  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && Date.now() - lastCheck > CHECK_EVERY_MS / 2) checkForMail();
  });

  $("signout").addEventListener("click", async () => {
    clearInterval(checkTimer);
    document.title = "Kylie Mail";
    await api("POST", "/api/signout").catch(() => {});
    closeMessage();
    $("message-list").replaceChildren();
    show("signin");
  });

  $("open-settings").addEventListener("click", () => openSettings());
  $("train").addEventListener("click", (event) => {
    const folders = [...$("filing-folders").querySelectorAll("input:checked")].map((box) => box.value);
    busy(event.target, async () => {
      try {
        await api("POST", "/api/filing/train", { folders });
        await loadFiling();
      } catch (e) {
        $("training-status").textContent = e.message;
      }
    });
  });
  $("stop-training").addEventListener("click", async () => {
    await api("POST", "/api/filing/stop").catch(() => {});
    await loadFiling().catch(() => {});
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
          openSettings().catch(() => {});
        });
      } catch (e) {
        setError(card, explain(e));
      }
    });
  });

  $("folder-select").addEventListener("change", (event) => openFolder(event.target.value));
  $("refresh").addEventListener("click", refresh);
  $("load-older").addEventListener("click", loadOlder);
  $("back").addEventListener("click", clearSelection);
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

  const list = $("message-list");
  list.addEventListener("click", onListClick);
  list.addEventListener("mousedown", (event) => {
    // Shift-click selects a range; don't let it also select text.
    if (event.shiftKey) event.preventDefault();
  });
  list.addEventListener("contextmenu", (event) => {
    const item = event.target.closest("li[data-key]");
    if (!item) return;
    event.preventDefault();
    const key = item.dataset.key;
    if (!mail.selected.has(key)) selectOnly(key);
    openMenu(event.clientX, event.clientY);
  });
  list.addEventListener("focus", () => {
    if (mail.cursor === null && mail.messages.length) {
      mail.cursor = mail.messages[0].key;
      markSelected();
    }
  });
  list.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && mail.cursor !== null) {
      if (inDrafts() && mail.selected.size === 1 && mail.selected.has(mail.cursor)) act("editDraft");
      else selectOnly(mail.cursor);
      event.preventDefault();
    }
  });

  $("toolbar").addEventListener("click", (event) => {
    const button = event.target.closest("[data-act]");
    if (button) act(button.dataset.act);
  });

  $("menu").addEventListener("keydown", onMenuKey);
  document.addEventListener("mousedown", (event) => {
    if (!$("menu").hidden && !$("menu").contains(event.target)) closeMenu();
  });
  window.addEventListener("blur", closeMenu);
  window.addEventListener("resize", closeMenu);
  list.addEventListener("scroll", closeMenu);

  $("picker-filter").addEventListener("input", renderPicker);
  $("picker-filter").addEventListener("keydown", onPickerKey);
  $("toast-undo").addEventListener("click", () => {
    const action = toastAction;
    hideToast();
    if (action) action.run();
  });

  // Search
  $("search").addEventListener("input", () => scheduleSearch());
  $("search").addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      mail.listId = "";
      scheduleSearch(true);
    } else if (event.key === "Escape") {
      event.preventDefault();
      const wasSearching = !!mail.search;
      endSearch();
      $("search").blur();
      if (wasSearching) openFolder(mail.folder);
      $("message-list").focus();
    } else if (event.key === "ArrowDown" && mail.messages.length) {
      event.preventDefault();
      $("message-list").focus();
      selectOnly(mail.messages[0].key);
    }
  });
  $("search-all").addEventListener("change", () => {
    mail.listId = "";
    scheduleSearch(true);
  });

  // Writing
  for (const id of ["new-message", "new-message-small"]) $(id).addEventListener("click", () => openCompose("new"));
  $("compose-form").addEventListener("submit", (event) => {
    event.preventDefault();
    busy($("c-send"), sendCompose);
  });
  $("compose").addEventListener("keydown", (event) => {
    if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
      event.preventDefault();
      busy($("c-send"), sendCompose);
    }
  });
  $("compose").addEventListener("cancel", (event) => {
    // Esc closes and saves a draft, unless it was just closing the address suggestions.
    event.preventDefault();
    if (!$("contact-suggest").hidden) return hideSuggest();
    closeCompose();
  });
  $("compose-close").addEventListener("click", closeCompose);
  // If the browser closes the window some other way (it can insist on Esc), keep the message as a draft.
  $("compose").addEventListener("close", () => {
    if (compose.handled) return;
    compose.handled = true;
    const draft = currentDraft();
    if (isEmptyDraft(draft)) return;
    api("POST", "/api/compose/save", draft)
      .then(() => toast("Saved to Drafts"))
      .catch((e) => toast(`Couldn't save the draft: ${e.message}`, 0, { label: "Open", run: () => openCompose(null, null, { draft, attachments: compose.files }) }));
  });
  $("c-discard").addEventListener("click", discardCompose);
  $("show-cc").addEventListener("click", () => {
    $("cc-row").hidden = $("bcc-row").hidden = false;
    $("show-cc").hidden = true;
    $("c-cc").focus();
  });
  $("c-attach").addEventListener("change", (event) => {
    attachFiles([...event.target.files]);
    event.target.value = "";
  });
  const dialog = $("compose");
  dialog.addEventListener("dragover", (event) => {
    if (![...event.dataTransfer.types].includes("Files")) return;
    event.preventDefault();
    dialog.classList.add("dropping");
  });
  dialog.addEventListener("dragleave", (event) => {
    if (event.target === dialog) dialog.classList.remove("dropping");
  });
  dialog.addEventListener("drop", (event) => {
    event.preventDefault();
    dialog.classList.remove("dropping");
    attachFiles([...event.dataTransfer.files]);
  });
  for (const input of document.querySelectorAll("#compose input.address")) {
    input.addEventListener("input", () => suggestFor(input));
    input.addEventListener("keydown", onAddressKey);
    input.addEventListener("blur", () => setTimeout(hideSuggest, 100));
  }

  $("identity-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const form = event.target;
    busy(form.querySelector("button"), async () => {
      try {
        await api("POST", "/api/compose/identity", { name: form.elements.name.value, signature: form.elements.signature.value });
        $("identity-saved").textContent = "Saved";
      } catch (e) {
        $("identity-saved").textContent = e.message;
      }
    });
  });

  document.addEventListener("keydown", onKey);

  start();
});
