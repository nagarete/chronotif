// The site's feedback form: posts to /feedback (worker/feedback.js) with Turnstile's token, and
// keeps the text when a send fails. Turnstile's token is single-use, so it's renewed after each try.
const form = document.querySelector(".feedback__form");
if (form) {
  const status = form.querySelector(".feedback__status");
  const button = form.querySelector("button[type=submit]");
  const say = (text, kind) => {
    status.textContent = text;
    status.dataset.kind = kind ?? "";
  };
  const answers = {
    400: "Something in the form wasn’t accepted. Check the message isn’t empty or too long.",
    403: "The bot check didn’t pass. Wait for it to finish, then send again.",
    413: "That’s too long to send. Shorten the message and try again.",
    429: "Too many messages from this network. Try again in a minute.",
  };

  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const data = new FormData(form);
    const text = String(data.get("text") ?? "").trim();
    if (!text) {
      say("Write a message first.", "error");
      form.elements.text.focus();
      return;
    }
    const token = String(data.get("cf-turnstile-response") ?? "");
    if (!token) {
      say("Wait a moment for the bot check to finish, then send again.", "error");
      return;
    }
    const body = { source: "web", text, token };
    const kind = data.get("kind");
    if (kind) body.kind = String(kind);
    const contact = String(data.get("contact") ?? "").trim();
    if (contact) body.contact = contact;

    button.disabled = true;
    say("Sending…");
    try {
      const res = await fetch("/feedback", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (res.ok) {
        form.reset();
        say("Sent. Thank you!", "good");
      } else {
        say(answers[res.status] ?? "Couldn’t send right now. Try again later, or email feedback@chronotif.com.", "error");
      }
    } catch {
      say("Couldn’t send. Check your connection and try again; your message is still here.", "error");
    } finally {
      button.disabled = false;
      window.turnstile?.reset();
    }
  });
}
