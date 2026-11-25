// An illustrative model of Chronotif's panel, mirroring core/Attention.kt in the app:
// one "last check" watermark splits notifications into NEW (arrived after it) and EARLIER
// (already checked, receded, still newest first; one app's neighbouring cards that arrived within
// 30 minutes of the newest of them fold into a stack). Media players sit on top;
// background work folds into a Running pill at the bottom.
// Times are in minutes. All content is made up.

const APPS = {
  messages: { name: "Messages", color: "#30a14e", glyph: "M" },
  calendar: { name: "Calendar", color: "#e5484d", glyph: "17" },
  mail: { name: "Mail", color: "#3b82f6", glyph: "@" },
  weather: { name: "Weather", color: "#0e9fb5", glyph: "°" },
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
      { id: "review", app: "calendar", title: "Design review", body: "In 15 minutes · Room 4", alert: 594 },
      { id: "ship", app: "mail", title: "Order update", body: "Your package has shipped.", alert: 575 },
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

function sections(state) {
  const byRecent = [...state.items].sort((a, b) => b.alert - a.alert);
  return {
    fresh: byRecent.filter((item) => item.alert > state.lastCheck),
    earlier: appStacks(byRecent.filter((item) => item.alert <= state.lastCheck)),
  };
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
    <div class="d-card ${extra}" data-vt="card-${item.id}">
      <span class="d-icon" data-bg="${app.color}" aria-hidden="true">${app.glyph}</span>
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
  return `<div role="listitem" class="d-card d-player" data-vt="card-${o.id}">
    <div class="d-player__top">
      <span class="d-art" aria-hidden="true"><span class="d-art__badge" data-bg="${app.color}">${app.glyph}</span></span>
      <div><div class="d-title">${esc(o.title)}</div><div class="d-body">${esc(o.body)}</div></div>
    </div>
    <div class="d-player__controls" aria-hidden="true">${svg(ICON.prev)}<span class="d-player__play">${svg(ICON.pause)}</span>${svg(ICON.next)}</div>
    <div class="d-seek" aria-hidden="true"><span>${o.elapsed}</span><div class="d-progress"><i data-width="${((e / t) * 100).toFixed(1)}%"></i></div><span>${o.total}</span></div>
  </div>`;
}

function render(state, list, fresh = new Set()) {
  const { fresh: newItems, earlier } = sections(state);
  let html = "";

  html += `<div class="d-list" role="list">`;
  html += state.media.map((o) => player(o)).join("");
  html += `</div>`;

  html += `<p class="d-head"><span>New<b>${newItems.length}</b></span></p>`;
  html += `<div class="d-list" role="list">`;
  html += newItems.length
    ? newItems.map((i) => `<div role="listitem">${card(state, i, fresh.has(i.id) ? "d-arrive" : "")}</div>`).join("")
    : `<div role="listitem" class="d-empty">Nothing new.</div>`;
  html += `</div>`;

  if (earlier.length) {
    html += `<p class="d-head"><span>Earlier</span><span class="d-clear" aria-hidden="true">Clear</span></p>`;
    html += `<div class="d-list" role="list">`;
    html += earlier
      .map((stack) => {
        const more = stack.length - 1;
        if (!more) return `<div role="listitem" class="d-receded">${card(state, stack[0])}</div>`;
        const label = `<span class="visually-hidden">, and ${more} more from ${APPS[stack[0].app].name}</span>`;
        return `<div role="listitem" class="d-receded d-stack" data-count="${Math.min(stack.length, 3)}">${card(state, stack[0])}${label}</div>`;
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
        moved
          ? `Panel closed. ${moved} notification${moved === 1 ? "" : "s"} moved to Earlier.`
          : "Panel closed. Nothing was new.",
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
