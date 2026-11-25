// Run with `npm test`: the checks and the email feedback.js builds, without Cloudflare's runtime.
import assert from "node:assert/strict";
import { test } from "node:test";
import { MAX_TEXT, mail, parse, replyAddress } from "./feedback.js";

test("takes what the app posts", () => {
  const { message } = parse(JSON.stringify({
    source: "app", kind: "problem", text: " Slow panel ", contact: " me@example.com ",
    details: { Chronotif: "0.3.1 (nightly)", Phone: "Xiaomi 2201117PG" },
  }));
  assert.deepEqual(message, {
    source: "app", kind: "problem", text: "Slow panel", contact: "me@example.com",
    details: [["Chronotif", "0.3.1 (nightly)"], ["Phone", "Xiaomi 2201117PG"]], token: null,
  });
});

test("refuses what it doesn't expect", () => {
  assert.ok(parse("nope").error);
  assert.ok(parse(JSON.stringify({ source: "app", text: "   " })).error);
  assert.ok(parse(JSON.stringify({ source: "app", text: "x".repeat(MAX_TEXT + 1) })).error);
  assert.ok(parse(JSON.stringify({ source: "app", text: "hi", kind: "spam" })).error);
  assert.ok(parse(JSON.stringify({ source: "bot", text: "hi" })).error);
  assert.ok(parse(JSON.stringify({ source: "web", text: "hi" })).error, "the site's form needs Turnstile's token");
  assert.ok(parse(JSON.stringify({ source: "app", text: "hi", details: { a: 1 } })).error);
});

test("replies go only to a plain address", () => {
  assert.equal(replyAddress("me@example.com"), "me@example.com");
  assert.equal(replyAddress("u/someone"), null);
  assert.equal(replyAddress("me@example.com\r\nBcc: x@example.com"), null);
  assert.equal(replyAddress("a <me@example.com>"), null);
});

test("the email keeps the sender's text out of its headers", () => {
  const { message } = parse(JSON.stringify({
    source: "app", text: "Subject: evil\r\nBcc: x@example.com\n\nrest", contact: "u/someone",
  }));
  const raw = mail(message, { from: "feedback@chronotif.com", to: "me@example.com", id: "id", date: new Date(0) });
  const [head, body] = raw.split("\r\n\r\n");
  assert.doesNotMatch(head, /Bcc|evil|Reply-To/);
  assert.match(head, /^Subject: =\?UTF-8\?B\?[A-Za-z0-9+/=]+\?=$/m);
  const text = Buffer.from(body.replace(/\r\n/g, ""), "base64").toString("utf8");
  assert.match(text, /Subject: evil/);
  assert.match(text, /Contact: u\/someone/);
});

test("an address left as the contact becomes Reply-To", () => {
  const { message } = parse(JSON.stringify({ source: "app", kind: "idea", text: "Écrire en français", contact: "me@example.com" }));
  const raw = mail(message, { from: "feedback@chronotif.com", to: "you@example.com", id: "id", date: new Date(0) });
  assert.match(raw, /^Reply-To: <me@example.com>$/m);
  const subject = raw.match(/^Subject: =\?UTF-8\?B\?(.+)\?=$/m)[1];
  assert.equal(Buffer.from(subject, "base64").toString("utf8"), "[Chronotif] Idea: Écrire en français");
});
