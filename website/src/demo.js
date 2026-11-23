// An illustrative model of Chronotif's panel, mirroring core/Attention.kt in the app:
// one "last check" watermark splits notifications into NEW (arrived after it) and EARLIER
// (already checked, receded, still newest first; one app's neighbouring cards that arrived within
// 30 minutes of the newest of them fold into a stack). A Priority app's cards sit in PRIORITY above
// NEW, stacked the same way, and a check doesn't move them; a Quiet app's go straight to EARLIER.
// Media players sit on top; background work folds into a Running pill at the bottom.
// Times are in minutes. All content is made up.

const APPS = {
  messages: { name: "Messages", color: "#30a14e", glyph: "M" },
  calendar: { name: "Calendar", color: "#e5484d", glyph: "17" },
  mail: { name: "Mail", color: "#3b82f6", glyph: "@" },
  weather: { name: "Weather", color: "#0e9fb5", glyph: "°" },
  bank: { name: "Bank", color: "#d9822b", glyph: "€", tier: "priority" },
  shop: { name: "Shop", color: "#c2410c", glyph: "%", tier: "quiet" },
  podcasts: { name: "Podcasts", color: "#8e4ec6", glyph: "♪\uFE0E" },
};

const MAYA_MESSAGES = [
  "Found a table by the window.",
  "Ordered you a coffee too.",
  "Are you close?",
];

function initialState() {
  return {
    now: 600,
    lastCheck: 560,
    arrivals: 0,
    media: [{ id: "pod", app: "podcasts", title: "Slow Mornings", body: "Episode 42", elapsed: "18:04", total: "41:30" }],
    running: 2,
    items: [
      { id: "maya", app: "messages", title: "Maya", body: "Running ten minutes late, save me a seat?", alert: 598 },
      { id: "sale", app: "shop", title: "Flash sale", body: "Up to 40% off until midnight.", alert: 596 },
      { id: "review", app: "calendar", title: "Design review", body: "In 15 minutes · Room 4", alert: 594 },
      { id: "ship", app: "mail", title: "Order update", body: "Your package has shipped.", alert: 575 },
      { id: "code", app: "bank", title: "Sign-in code", body: "Your code is 482 913. Valid for 10 minutes.", alert: 548 },
      { id: "paid", app: "bank", title: "Card payment", body: "€12.40 at Corner Café.", alert: 532 },
      { id: "rain", app: "weather", title: "Rain this evening", body: "Light rain from 6 PM.", alert: 520 },
      { id: "ana", app: "messages", title: "Ana", body: "See you tomorrow!", alert: 470 },
      { id: "climb", app: "messages", title: "Climbing group", body: "Leo: Thursday works for me", alert: 410 },
      { id: "digest", app: "mail", title: "Weekly digest", body: "Five things worth reading this week.", alert: 380 },
      { id: "receipt", app: "mail", title: "Receipt", body: "Thanks for your order.", alert: 366 },
      { id: "invoice", app: "mail", title: "Invoice", body: "Your invoice is ready.", alert: 280 },
    ],
  };
}

const STACK_WINDOW = 30;

// One app's neighbouring cards (newest first) join a stack while within STACK_WINDOW of its newest card.
function appStacks(cards) {
  const stacks = [];
  for (const card of cards) {
    const stack = stacks.at(-1);
    if (stack && stack[0].app === card.app && stack[0].alert - card.alert <= STACK_WINDOW) stack.push(card);
    else stacks.push([card]);
  }
  return stacks;
}

// A Priority app's cards stay in PRIORITY whatever the last check (in the app, until opened); a Quiet
// app's are never New.
const tier = (item) => APPS[item.app].tier ?? "normal";

function sections(state) {
  const byRecent = [...state.items].sort((a, b) => b.alert - a.alert);
  const priority = byRecent.filter((item) => tier(item) === "priority");
  const rest = byRecent.filter((item) => tier(item) !== "priority");
  const isNew = (item) => tier(item) !== "quiet" && item.alert > state.lastCheck;
  return {
    priority: appStacks(priority),
    fresh: rest.filter(isNew),
    earlier: appStacks(rest.filter((item) => !isNew(item))),
  };
}

// A stack drawn as its newest card with slivers below; [cls] is "d-receded" in Earlier.
function stackItem(state, stack, cls = "") {
  const more = stack.length - 1;
  if (!more) return `<div role="listitem" class="${cls}">${card(state, stack[0])}</div>`;
  const label = `<span class="visually-hidden">, and ${more} more from ${APPS[stack[0].app].name}</span>`;
  return `<div role="listitem" class="${cls} d-stack" data-count="${Math.min(stack.length, 3)}">${card(state, stack[0])}${label}</div>`;
}

function ago(state, t) {
  const m = state.now - t;
  if (m < 1) return "now";
  if (m < 60) return `${m}m`;
  return `${Math.floor(m / 60)}h`;
}

const esc = (s) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

function card(state, item, extra = "") {
  const app = APPS[item.app];
  return `
    <div class="d-card ${extra}" style="view-transition-name: card-${item.id}">
      <span class="d-icon" style="background:${app.color}" aria-hidden="true">${app.glyph}</span>
      <div>
        <div class="d-row">
          <span class="d-title">${esc(item.title)}</span>
          <span class="d-time">${ago(state, item.alert)}</span>
        </div>
        <div class="d-body">${esc(item.body)}</div>
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
  return `<div role="listitem" class="d-card d-player" style="view-transition-name: card-${o.id}">
    <div class="d-player__top">
      <span class="d-art" aria-hidden="true"><span class="d-art__badge" style="background:${app.color}">${app.glyph}</span></span>
      <div><div class="d-title">${esc(o.title)}</div><div class="d-body">${esc(o.body)}</div></div>
    </div>
    <div class="d-player__controls" aria-hidden="true">${svg(ICON.prev)}<span class="d-player__play">${svg(ICON.pause)}</span>${svg(ICON.next)}</div>
    <div class="d-seek" aria-hidden="true"><span>${o.elapsed}</span><div class="d-progress"><i style="width:${((e / t) * 100).toFixed(1)}%"></i></div><span>${o.total}</span></div>
  </div>`;
}

function render(state, list, fresh = new Set()) {
  const { priority, fresh: newItems, earlier } = sections(state);
  let html = "";

  html += `<div class="d-list" role="list">`;
  html += state.media.map((o) => player(o)).join("");
  html += `</div>`;

  if (priority.length) {
    const count = priority.reduce((n, stack) => n + stack.length, 0);
    html += `<p class="d-head d-head--priority"><span>Priority<b>${count}</b></span></p>`;
    html += `<div class="d-list" role="list">${priority.map((stack) => stackItem(state, stack)).join("")}</div>`;
  }

  html += `<p class="d-head"><span>New<b>${newItems.length}</b></span></p>`;
  html += `<div class="d-list" role="list">`;
  html += newItems.length
    ? newItems.map((i) => `<div role="listitem">${card(state, i, fresh.has(i.id) ? "d-arrive" : "")}</div>`).join("")
    : `<div role="listitem" class="d-empty">Nothing new.</div>`;
  html += `</div>`;

  if (earlier.length) {
    html += `<p class="d-head"><span>Earlier</span><span class="d-clear" aria-hidden="true">Clear</span></p>`;
    html += `<div class="d-list" role="list">`;
    html += earlier.map((stack) => stackItem(state, stack, "d-receded")).join("");
    html += `</div>`;
  }

  if (state.running) html += `<p class="d-running"><span class="d-clear">${state.running} running</span></p>`;

  list.innerHTML = html;
}

export function initDemo(root) {
  const list = root.querySelector("#demo-list");
  const status = root.querySelector("#demo-status");
  if (!list) return;

  let state = initialState();
  const motionOK = () => !window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  const update = (mutate, message) => {
    const before = new Set(sections(state).fresh.map((i) => i.id));
    mutate();
    const arrived = new Set(sections(state).fresh.map((i) => i.id).filter((id) => !before.has(id)));
    const draw = () => render(state, list, motionOK() ? arrived : new Set());
    if (document.startViewTransition && motionOK()) document.startViewTransition(draw);
    else draw();
    status.textContent = message;
  };

  const actions = {
    check() {
      const moved = sections(state).fresh.length;
      update(
        () => {
          state.now += 1;
          state.lastCheck = state.now;
        },
        (moved
          ? `Panel closed. ${moved} notification${moved === 1 ? "" : "s"} moved to Earlier.`
          : "Panel closed. Nothing was new.") +
          (sections(state).priority.length ? " Priority stays until opened." : ""),
      );
    },
    arrive() {
      const maya = state.items.find((i) => i.id === "maya");
      const wasEarlier = maya.alert <= state.lastCheck;
      update(
        () => {
          state.now += 2;
          maya.body = MAYA_MESSAGES[state.arrivals++ % MAYA_MESSAGES.length];
          maya.alert = state.now;
        },
        wasEarlier
          ? "A new message from Maya. Her conversation moved back to New."
          : "A new message from Maya. Her conversation is at the top of New.",
      );
    },
    reset() {
      update(() => {
        state = initialState();
      }, "Illustration reset.");
    },
  };

  root.addEventListener("click", (e) => {
    const button = e.target.closest("[data-demo]");
    if (button) actions[button.dataset.demo]?.();
  });

  render(state, list);
}
