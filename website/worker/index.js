// Serves dist/, sends www.chronotif.com to the apex domain, adds security headers, answers
// Range requests (Safari won't play a video whose server ignores them; static assets alone return 200),
// and takes Feedback posted to /feedback (feedback.js).
// run_worker_first in wrangler.jsonc sends every request here, so the headers reach assets too.

import { EmailMessage } from "cloudflare:email";
import { MAX_BODY, mail, parse } from "./feedback.js";

// Byte sizes of dist/video/*, written by `npm run build` (vite.config.js). The assets binding
// streams a file without a Content-Length header, and a range needs the size up front.
import VIDEO_SIZES from "./video-sizes.json";

// The site loads only its own files: Vite's script and stylesheet, self-hosted fonts, images
// and videos. The JSON-LD block is data, not script, so script-src needs no inline allowance.
// src/demo.js sets its inline styles through element.style, which style-src doesn't restrict.
const CSP = [
  "default-src 'none'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self'",
  "font-src 'self'",
  "media-src 'self'",
  "connect-src 'self'",
  "manifest-src 'self'",
  "base-uri 'none'",
  "form-action 'none'",
  "frame-ancestors 'none'",
].join("; ");

// The feedback page also runs Cloudflare Turnstile, the check that keeps bots off the form:
// its script and the frame it draws in come from challenges.cloudflare.com.
const TURNSTILE = "https://challenges.cloudflare.com";
const FEEDBACK_CSP = CSP
  .replace("script-src 'self'", `script-src 'self' ${TURNSTILE}`)
  .replace("default-src 'none'", `default-src 'none'; frame-src ${TURNSTILE}`);

const SECURITY_HEADERS = {
  "Content-Security-Policy": CSP,
  "X-Content-Type-Options": "nosniff",
  // Links out (GitHub, Ko-fi) still show chronotif.com as the referrer, but never the path.
  "Referrer-Policy": "strict-origin-when-cross-origin",
  "Cross-Origin-Opener-Policy": "same-origin",
  // Fullscreen and picture-in-picture stay at their defaults for the release videos.
  "Permissions-Policy": [
    "accelerometer=()", "browsing-topics=()", "camera=()", "display-capture=()",
    "geolocation=()", "gyroscope=()", "hid=()", "magnetometer=()", "microphone=()",
    "midi=()", "payment=()", "publickey-credentials-get=()", "serial=()", "usb=()",
    "xr-spatial-tracking=()",
  ].join(", "),
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.hostname === "www.chronotif.com") {
      url.hostname = "chronotif.com";
      return secure(new Response(null, { status: 301, headers: { Location: url.toString() } }));
    }
    const feedbackPage = url.pathname === "/feedback" || url.pathname === "/feedback.html";
    if (url.pathname === "/feedback" && request.method === "POST") {
      return secure(await feedback(request, env));
    }
    const response = await env.ASSETS.fetch(request);
    const range = request.headers.get("Range");
    if (!range || response.status !== 200 || !url.pathname.startsWith("/video/")) {
      return secure(response, feedbackPage ? FEEDBACK_CSP : CSP);
    }
    return secure(serveRange(request, response, range, VIDEO_SIZES[url.pathname]));
  },
};

function secure(response, csp = CSP) {
  // Responses from the assets binding have immutable headers, so copy before setting.
  const out = new Response(response.body, response);
  for (const [name, value] of Object.entries(SECURITY_HEADERS)) out.headers.set(name, value);
  out.headers.set("Content-Security-Policy", csp);
  return out;
}

const FEEDBACK_FROM = "feedback@chronotif.com";

function answer(status, error) {
  return Response.json(error ? { ok: false, error } : { ok: true }, {
    status,
    headers: { "Cache-Control": "no-store" },
  });
}

/**
 * Feedback from the app or the site's form, emailed to the maintainer (FEEDBACK_TO, a Worker
 * secret, so the address isn't in the repository). The sender's IP address is only the key for
 * FEEDBACK_LIMIT and Turnstile's check; it isn't kept or put in the email.
 */
async function feedback(request, env) {
  if (!env.FEEDBACK_MAIL || !env.FEEDBACK_TO || !env.TURNSTILE_SECRET || !env.FEEDBACK_LIMIT) {
    console.error("feedback: a binding or secret is missing");
    return answer(503, "not set up");
  }
  const ip = request.headers.get("CF-Connecting-IP") ?? "";
  // The rate limit is the guard for both forms: the app has no Turnstile, and anyone can post as it.
  const { success } = await env.FEEDBACK_LIMIT.limit({ key: ip });
  if (!success) return answer(429, "too many");

  const json = await readCapped(request, MAX_BODY);
  if (json === null) return answer(413, "too large");
  const { message, error } = parse(json);
  if (error) return answer(400, error);

  if (message.source === "web" && !(await turnstile(message.token, ip, env.TURNSTILE_SECRET))) {
    return answer(403, "check failed");
  }
  const raw = mail(message, { from: FEEDBACK_FROM, to: env.FEEDBACK_TO, id: crypto.randomUUID(), date: new Date() });
  try {
    await env.FEEDBACK_MAIL.send(new EmailMessage(FEEDBACK_FROM, env.FEEDBACK_TO, raw));
  } catch (e) {
    console.error(`feedback: couldn't send (${e.message})`);
    return answer(502, "not sent");
  }
  return answer(200);
}

// The body read as it arrives, given up as soon as it passes [max] bytes, so a huge post is never held.
async function readCapped(request, max) {
  if (Number(request.headers.get("Content-Length") ?? 0) > max) return null;
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > max) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let at = 0;
  for (const c of chunks) {
    bytes.set(c, at);
    at += c.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

async function turnstile(token, ip, secret) {
  const form = new FormData();
  form.append("secret", secret);
  form.append("response", token);
  if (ip) form.append("remoteip", ip);
  try {
    const res = await fetch(`${TURNSTILE}/turnstile/v0/siteverify`, { method: "POST", body: form });
    return (await res.json()).success === true;
  } catch {
    return false;
  }
}

// One byte range of the asset, read as a stream: the bytes before it are skipped as they pass and
// the read stops at its end, so a request never holds more than one chunk of the video in memory.
function serveRange(request, response, range, size) {
  const encoding = response.headers.get("Content-Encoding");
  const sent = response.headers.get("Content-Length");
  const ifRange = request.headers.get("If-Range");
  // Without a known identity size the offsets can't be checked, and an If-Range that no longer
  // matches asks for the whole new file (RFC 9110 §13.1.5). A 200 with the full body is valid then.
  if (!Number.isSafeInteger(size) || size <= 0 || (sent !== null && Number(sent) !== size) ||
      (encoding && encoding !== "identity") || (ifRange && ifRange !== response.headers.get("ETag"))) {
    return response;
  }

  const bounds = parseRange(range, size);
  const headers = new Headers(response.headers);
  headers.set("Accept-Ranges", "bytes");
  if (!bounds) {
    response.body?.cancel();
    for (const name of ["Content-Length", "Content-Type", "Content-Encoding", "ETag", "Last-Modified"]) {
      headers.delete(name);
    }
    headers.set("Content-Range", `bytes */${size}`);
    return new Response(null, { status: 416, headers });
  }

  const [start, end] = bounds;
  const length = end - start + 1;
  headers.set("Content-Range", `bytes ${start}-${end}/${size}`);
  headers.set("Content-Length", String(length));
  if (request.method === "HEAD" || !response.body) {
    response.body?.cancel();
    return new Response(null, { status: 206, headers });
  }
  // FixedLengthStream makes the runtime send Content-Length instead of chunked encoding.
  const body = response.body.pipeThrough(cut(start, length)).pipeThrough(new FixedLengthStream(length));
  return new Response(body, { status: 206, headers });
}

// A single range: "bytes=start-end", "bytes=start-" or the suffix "bytes=-count". Anything else,
// including several ranges or a start past the end, is refused with 416.
function parseRange(range, size) {
  const match = /^bytes=(\d*)-(\d*)$/.exec(range.trim());
  if (!match || (match[1] === "" && match[2] === "")) return null;
  if (match[1] === "") {
    const count = Number(match[2]);
    if (!Number.isSafeInteger(count) || count === 0) return null;
    return [Math.max(size - count, 0), size - 1];
  }
  const start = Number(match[1]);
  const end = match[2] === "" ? size - 1 : Math.min(Number(match[2]), size - 1);
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start >= size || start > end) {
    return null;
  }
  return [start, end];
}

function cut(start, length) {
  let skip = start;
  let left = length;
  return new TransformStream({
    transform(chunk, controller) {
      const bytes = chunk instanceof Uint8Array ? chunk : new Uint8Array(chunk);
      if (skip >= bytes.byteLength) {
        skip -= bytes.byteLength;
        return;
      }
      const part = bytes.subarray(skip, skip + left);
      skip = 0;
      left -= part.byteLength;
      controller.enqueue(part);
      // Closing here cancels the rest of the asset read.
      if (left === 0) controller.terminate();
    },
  });
}
