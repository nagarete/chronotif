// An illustrative model of Chronotif's panel, mirroring core/Attention.kt and ui/NotifList.kt in the app:
// one "last check" watermark splits notifications into NEW (arrived after it) and EARLIER
// (already checked, receded, still newest first). A chat lists its messages, one card each, and is
// split at the check the same way: messages since it stack in New, checked ones stack in Earlier
// (ADR 0002). In Earlier, one app's neighbouring cards that arrived within 30 minutes of the newest
// of them fold into a stack (ADR 0004). Earlier's Clear keeps a chat's New messages (ADR 0019).
// Media players sit on top; background work folds into a Running pill at the bottom.
// Times are in minutes. All content is made up.

const APPS = {
  messages: { name: "Messages", color: "#30a14e", glyph: "M" },
  calendar: { name: "Calendar", color: "#e5484d", glyph: "17" },
  mail: { name: "Mail", color: "#3b82f6", glyph: "@" },
  weather: { name: "Weather", color: "#0e9fb5", glyph: "°" },
  podcasts: { name: "Podcasts", color: "#8e4ec6", glyph: "♪︎" },
};

const MAYA_MESSAGES = ["Parking now.", "Two minutes away.", "Here! Where are you sitting?"];

// Android keeps a chat's latest 25 messages in its notification (MessagingStyle).
const MAX_MESSAGES = 25;

const chat = (id, title, messages) => ({
  id,
  app: "messages",
  title,
  alert: messages.at(-1)[1],
  messages: messages.map(([body, time], i) => ({ id: i + 1, body, time })),
});

function initialState() {
  return {
    now: 600,
    lastCheck: 560,
    arrivals: 0,
    // Message cards swiped or cleared away, as "chat#message": the chat itself stays.
    hidden: new Set(),
    media: [{ id: "pod", app: "podcasts", title: "Slow Mornings", body: "Episode 42", elapsed: "18:04", total: "41:30" }],
    running: 2,
    items: [
      chat("maya", "Maya", [
        ["Lunch at Rosa’s at 12?", 540],
        ["Running ten minutes late, save me a seat?", 598],
      ]),
      { id: "review", app: "calendar", title: "Design review", body: "In 15 minutes · Room 4", alert: 594 },
      { id: "ship", app: "mail", title: "Order update", body: "Your package has shipped.", alert: 575 },
      { id: "rain", app: "weather", title: "Rain this evening", body: "Light rain from 6 PM.", alert: 520 },
      chat("ana", "Ana", [["See you tomorrow!", 470]]),
      chat("climb", "Climbing group", [
        ["Mia: Who’s in for Thursday?", 396],
        ["Noor: Me!", 403],
        ["Leo: Thursday works for me", 410],
      ]),
      { id: "digest", app: "mail", title: "Weekly digest", body: "Five things worth reading this week.", alert: 380 },
      { id: "receipt", app: "mail", title: "Receipt", body: "Thanks for your order.", alert: 366 },
      { id: "invoice", app: "mail", title: "Invoice", body: "Your invoice is ready.", alert: 280 },
    ],
  };
}

const STACK_WINDOW = 30;

// Attention.newMessages: those sent after the check, and at least the latest, since its arrival made the chat New.
function newMessages(times, lastCheck) {
  const after = times.filter((t) => t > lastCheck).length;
  return Math.min(Math.max(after, Math.min(1, times.length)), times.length);
}

// Attention.split: which messages are New is decided before hiding, so hiding the only New one
// leaves nothing New rather than moving a checked message up.
function split(item, state) {
  const { messages } = item;
  const newCount = item.alert > state.lastCheck ? newMessages(messages.map((m) => m.time), state.lastCheck) : 0;
  const cut = messages.length - newCount;
  const shown = (m) => !state.hidden.has(`${item.id}#${m.id}`);
  return { fresh: messages.slice(cut).filter(shown), earlier: messages.slice(0, cut).filter(shown) };
}

// One card or Conversation stack. It shows, and sorts by, its newest message's time. `whole` is false
// for the Earlier part of a chat that also has messages in New.
function piece(item, msgs = null, whole = true) {
  const last = msgs?.at(-1);
  return {
    item,
    msgs,
    whole,
    time: last ? last.time : item.alert,
    key: last ? `${item.id}-${last.id}` : item.id,
    // A chat showing several messages is already its own stack, so it never joins an app stack.
    app: msgs && msgs.length > 1 ? null : item.app,
  };
}

// Attention.appStacks: a card joins the stack above it only when it comes straight after it, from the
// same app, within STACK_WINDOW of the stack's newest card.
function appStacks(pieces) {
  const stacks = [];
  for (const p of pieces) {
    const stack = stacks.at(-1);
    if (stack && p.app && stack[0].app === p.app && stack[0].time - p.time <= STACK_WINDOW) stack.push(p);
    else stacks.push([p]);
  }
  return stacks;
}

function sections(state) {
  const fresh = [];
  const earlier = [];
  for (const item of state.items) {
    if (!item.messages) {
      (item.alert > state.lastCheck ? fresh : earlier).push(piece(item));
      continue;
    }
    const s = split(item, state);
    if (s.fresh.length) fresh.push(piece(item, s.fresh));
    if (s.earlier.length) earlier.push(piece(item, s.earlier, !s.fresh.length));
  }
  const newestFirst = (a, b) => b.time - a.time;
  return { fresh: fresh.sort(newestFirst), earlier: appStacks(earlier.sort(newestFirst)) };
}

function ago(state, t) {
  const m = state.now - t;
  if (m < 1) return "now";
  if (m < 60) return `${m}m`;
  return `${Math.floor(m / 60)}h`;
}

const esc = (s) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

function card(state, p, extra = "") {
  const app = APPS[p.item.app];
  const body = p.msgs ? p.msgs.at(-1).body : p.item.body;
  // A collapsed Conversation stack carries its message count on the icon, as the app does on the Avatar.
  const count = p.msgs?.length > 1 ? `<span class="d-count">${p.msgs.length}</span>` : "";
  return `
    <div class="d-card ${extra}" data-vt="card-${p.key}">
      <span class="d-icon" data-bg="${app.color}" aria-hidden="true">${app.glyph}${count}</span>
      <div>
        <div class="d-row">
          <span class="d-title">${esc(p.item.title)}</span>
          <span class="d-time">${ago(state, p.time)}</span>
        </div>
        <div class="d-body">${esc(body)}</div>
      </div>
    </div>`;
}

// Drawn like the app's Media player: artwork badged with the app, title and artist,
// previous / pause / next, and a seek bar with elapsed and total time.
const ICON = {
  prev: "M6 5h2v14H6zM20 5v14L9 12z",
  pause: "M7 5h4v14H7zM13 5h4v14h-4z",
  next: "M16 5h2v14h-2zM4 5l11 7-11 7z",
};
const svg = (d) => `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${d}"/></svg>`;

function player(o) {
  const app = APPS[o.app];
  const [e, t] = [o.elapsed, o.total].map((x) => x.split(":").reduce((m, s) => m * 60 + Number(s), 0));
  return `<div role="listitem" class="d-card d-player" data-vt="card-${o.id}">
    <div class="d-player__top">
      <span class="d-art" aria-hidden="true"><span class="d-art__badge" data-bg="${app.color}">${app.glyph}</span></span>
      <div><div class="d-title">${esc(o.title)}</div><div class="d-body">${esc(o.body)}</div></div>
    </div>
    <div class="d-player__controls" aria-hidden="true">${svg(ICON.prev)}<span class="d-player__play">${svg(ICON.pause)}</span>${svg(ICON.next)}</div>
    <div class="d-seek" aria-hidden="true"><span>${o.elapsed}</span><div class="d-progress"><i data-width="${((e / t) * 100).toFixed(1)}%"></i></div><span>${o.total}</span></div>
  </div>`;
}

// A collapsed stack: its newest card with slivers underneath, and what's folded under it for screen readers.
function stacked(cardHtml, size, label, cls) {
  const hidden = `<span class="visually-hidden">, ${esc(label)}</span>`;
  return `<div role="listitem" class="${cls} d-stack" data-count="${Math.min(size, 3)}">${cardHtml}${hidden}</div>`;
}

const moreMessages = (n) => `and ${n} more message${n === 1 ? "" : "s"}`;

function render(state, list, arrived = new Set()) {
  const { fresh, earlier } = sections(state);
  let html = "";

  html += `<div class="d-list" role="list">`;
  html += state.media.map((o) => player(o)).join("");
  html += `</div>`;

  // New is left out when nothing in it shows, as in the app.
  if (fresh.length) {
    html += `<p class="d-head"><span>New<b>${fresh.length}</b></span></p>`;
    html += `<div class="d-list" role="list">`;
    html += fresh
      .map((p) => {
        const c = card(state, p, arrived.has(p.key) ? "d-arrive" : "");
        const n = p.msgs ? p.msgs.length - 1 : 0;
        return n ? stacked(c, n + 1, moreMessages(n), "d-stack--new") : `<div role="listitem">${c}</div>`;
      })
      .join("");
    html += `</div>`;
  }

  if (earlier.length) {
    html += `<p class="d-head"><span>Earlier</span><button type="button" class="d-clear" data-demo="clear" aria-label="Clear Earlier">Clear</button></p>`;
    html += `<div class="d-list" role="list">`;
    html += earlier
      .map((stack) => {
        const top = stack[0];
        const c = card(state, top);
        if (stack.length > 1) {
          const more = stack.length - 1;
          return stacked(c, stack.length, `and ${more} more from ${APPS[top.item.app].name}`, "d-receded");
        }
        const n = top.msgs ? top.msgs.length - 1 : 0;
        return n ? stacked(c, n + 1, moreMessages(n), "d-receded") : `<div role="listitem" class="d-receded">${c}</div>`;
      })
      .join("");
    html += `</div>`;
  }

  if (state.running) html += `<p class="d-running"><span class="d-clear">${state.running} running</span></p>`;

  list.innerHTML = html;
  // The site's Content-Security-Policy blocks style="" attributes in markup but not styles set
  // through the DOM, so per-card styles travel as data attributes and are applied here.
  for (const el of list.querySelectorAll("[data-vt]")) el.style.viewTransitionName = el.dataset.vt;
  for (const el of list.querySelectorAll("[data-bg]")) el.style.background = el.dataset.bg;
  for (const el of list.querySelectorAll("[data-width]")) el.style.width = el.dataset.width;
}

const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;

export function initDemo(root) {
  const list = root.querySelector("#demo-list");
  const status = root.querySelector("#demo-status");
  if (!list) return;

  let state = initialState();
  const motionOK = () => !window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  // `mutate` changes the state and returns what to announce.
  const update = (mutate) => {
    const before = new Set(sections(state).fresh.map((p) => p.key));
    const message = mutate();
    const arrived = new Set(sections(state).fresh.map((p) => p.key).filter((key) => !before.has(key)));
    const clearFocused = document.activeElement?.dataset?.demo === "clear";
    const draw = () => {
      render(state, list, motionOK() ? arrived : new Set());
      // The list is redrawn, so a keyboard user's focus on Clear would drop to the page.
      if (clearFocused) (list.querySelector("[data-demo=clear]") ?? root.querySelector("[data-demo=check]"))?.focus();
    };
    if (document.startViewTransition && motionOK()) document.startViewTransition(draw);
    else draw();
    status.textContent = message;
  };

  const actions = {
    check() {
      update(() => {
        const moved = sections(state).fresh.length;
        state.now += 1;
        state.lastCheck = state.now;
        return moved
          ? `Panel closed. ${plural(moved, "notification")} moved to Earlier.`
          : "Panel closed. Nothing was new.";
      });
    },
    arrive() {
      update(() => {
        const wasNew = sections(state).fresh.some((p) => p.item.id === "maya");
        state.now += 2;
        let maya = state.items.find((i) => i.id === "maya");
        // Cleared earlier: the app posts the chat again.
        if (!maya) state.items.push((maya = { id: "maya", app: "messages", title: "Maya", messages: [] }));
        const id = (maya.messages.at(-1)?.id ?? 0) + 1;
        maya.messages.push({ id, body: MAYA_MESSAGES[state.arrivals++ % MAYA_MESSAGES.length], time: state.now });
        if (maya.messages.length > MAX_MESSAGES) maya.messages.shift();
        maya.alert = state.now;
        if (wasNew) return "Another message from Maya. It stacks with her new messages at the top of New.";
        const seen = sections(state).earlier.flat().some((p) => p.item.id === "maya");
        return seen
          ? "A new message from Maya, at the top of New. Her messages you’ve seen stay in Earlier."
          : "A new message from Maya, at the top of New.";
      });
    },
    clear() {
      update(() => {
        const pieces = sections(state).earlier.flat();
        const kept = [];
        for (const p of pieces) {
          // The Earlier part of a chat also in New hides just its messages (ADR 0019).
          if (!p.whole) {
            for (const m of p.msgs) state.hidden.add(`${p.item.id}#${m.id}`);
            kept.push(p.item.title);
          } else {
            state.items = state.items.filter((i) => i !== p.item);
            // A chat posted again starts its message ids over, so its old hidden ones must go.
            for (const h of state.hidden) if (h.startsWith(`${p.item.id}#`)) state.hidden.delete(h);
          }
        }
        return kept.length
          ? `Earlier cleared. New messages from ${kept.join(" and ")} stay in New.`
          : "Earlier cleared.";
      });
    },
    reset() {
      update(() => {
        state = initialState();
        return "Illustration reset.";
      });
    },
  };

  root.addEventListener("click", (e) => {
    const button = e.target.closest("[data-demo]");
    if (button) actions[button.dataset.demo]?.();
  });

  render(state, list);
}
