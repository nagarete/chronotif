// Feedback (GLOSSARY): a message posted to /feedback, from the app's Send feedback page or the
// site's form, checked here and written as an email to the maintainer. It's never stored or posted
// publicly. The app holds the same limits (core/FeedbackMessage.kt).

export const MAX_BODY = 16_384;
export const MAX_TEXT = 4000;
export const MAX_CONTACT = 200;
const MAX_DETAILS = 8;
const MAX_DETAIL = 200;
const KINDS = new Set(["problem", "idea", "other"]);
const SOURCES = new Set(["app", "web"]);

/** The posted JSON as a message, or `{ error }` for anything it doesn't expect. */
export function parse(json) {
  let data;
  try {
    data = JSON.parse(json);
  } catch {
    return { error: "not JSON" };
  }
  if (!data || typeof data !== "object" || Array.isArray(data)) return { error: "not an object" };
  const { source, kind, text, contact, details, token } = data;
  if (!SOURCES.has(source)) return { error: "unknown source" };
  if (kind !== undefined && !KINDS.has(kind)) return { error: "unknown kind" };
  if (typeof text !== "string" || !text.trim() || text.trim().length > MAX_TEXT) return { error: "text" };
  if (contact !== undefined && (typeof contact !== "string" || contact.trim().length > MAX_CONTACT)) {
    return { error: "contact" };
  }
  if (details !== undefined) {
    if (!details || typeof details !== "object" || Array.isArray(details)) return { error: "details" };
    const entries = Object.entries(details);
    if (entries.length > MAX_DETAILS || entries.some(([k, v]) =>
      typeof v !== "string" || k.length > MAX_DETAIL || v.length > MAX_DETAIL)) {
      return { error: "details" };
    }
  }
  if (source === "web" && typeof token !== "string") return { error: "token" };
  return {
    message: {
      source,
      kind: kind ?? null,
      text: text.trim(),
      contact: contact?.trim() || null,
      details: details ? Object.entries(details) : null,
      token: source === "web" ? token : null,
    },
  };
}

/** The contact as an address to reply to, when it is one. Never anything that could add a header. */
export function replyAddress(contact) {
  if (!contact || /[\r\n]/.test(contact)) return null;
  return /^[^\s@<>()",;:\\[\]]+@[^\s@<>()",;:\\[\]]+\.[^\s@<>()",;:\\[\]]+$/.test(contact) ? contact : null;
}

const KIND_NAMES = { problem: "Problem", idea: "Idea", other: "Other" };

/** The email: plain text, UTF-8, with every header value built here or checked by [replyAddress]. */
export function mail(message, { from, to, id, date }) {
  const kind = KIND_NAMES[message.kind] ?? "Feedback";
  const firstLine = message.text.split(/\r?\n/, 1)[0];
  const subject = `[Chronotif] ${kind}: ${firstLine.length > 60 ? `${firstLine.slice(0, 59)}…` : firstLine}`;
  const reply = replyAddress(message.contact);

  const lines = [
    `${kind}, sent from ${message.source === "app" ? "the app" : "chronotif.com"}`,
    "",
    message.text,
    "",
    `Contact: ${message.contact ?? "none left"}`,
  ];
  if (message.details) {
    lines.push("", ...message.details.map(([k, v]) => `${k}: ${v}`));
  }
  lines.push("", "Reply only to the contact above. Anything filed on GitHub from this is public, so leave out what they'd want private.");

  const headers = [
    `From: Chronotif feedback <${from}>`,
    `To: <${to}>`,
    ...(reply ? [`Reply-To: <${reply}>`] : []),
    `Subject: ${encodeWord(subject)}`,
    `Date: ${date.toUTCString()}`,
    `Message-ID: <${id}@chronotif.com>`,
    "MIME-Version: 1.0",
    "Content-Type: text/plain; charset=utf-8",
    "Content-Transfer-Encoding: base64",
  ];
  return `${headers.join("\r\n")}\r\n\r\n${wrap(base64(lines.join("\r\n")))}\r\n`;
}

// RFC 2047: a header carries any language's text as base64 of its UTF-8, which also leaves no
// line break a sender could use to start a header of their own.
function encodeWord(s) {
  return `=?UTF-8?B?${base64(s)}?=`;
}

function base64(s) {
  let binary = "";
  for (const b of new TextEncoder().encode(s)) binary += String.fromCharCode(b);
  return btoa(binary);
}

function wrap(s) {
  return s.match(/.{1,76}/g)?.join("\r\n") ?? "";
}
