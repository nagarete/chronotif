import { initDemo } from "./demo.js";

document.documentElement.classList.add("js");

const demo = document.querySelector(".demo");
if (demo) initDemo(demo);

// A release video in parts: a part in the list plays in the one player.
for (const list of document.querySelectorAll(".parts")) {
  const video = list.parentElement.querySelector("video");
  list.addEventListener("click", (e) => {
    const item = e.target.closest(".parts__item");
    if (!item || !video) return;
    e.preventDefault();
    for (const other of list.querySelectorAll(".parts__item")) {
      other.classList.toggle("is-current", other === item);
      if (other === item) other.setAttribute("aria-current", "true");
      else other.removeAttribute("aria-current");
    }
    video.poster = item.dataset.poster;
    video.setAttribute("aria-label", item.dataset.label);
    video.querySelector("source").src = item.getAttribute("href");
    video.load();
    video.play().catch(() => {});
  });
}

// Hairline under the masthead once the page scrolls.
const masthead = document.querySelector(".masthead");
const onScroll = () => masthead.classList.toggle("is-scrolled", window.scrollY > 8);
window.addEventListener("scroll", onScroll, { passive: true });
onScroll();

// Gentle fade-in for section content. Skipped entirely when the user prefers reduced motion.
if (!window.matchMedia("(prefers-reduced-motion: reduce)").matches && "IntersectionObserver" in window) {
  const targets = document.querySelectorAll(".section__head, .gallery > li, .model > *, .ways > li, .real, .pillars, .steps > li, .asides, .faq__list, .closing");
  const io = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.classList.add("is-visible");
        io.unobserve(entry.target);
      }
    },
    { rootMargin: "0px 0px -8% 0px", threshold: 0.05 },
  );
  for (const el of targets) {
    el.classList.add("reveal");
    io.observe(el);
  }
}
