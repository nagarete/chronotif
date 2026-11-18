// Serves dist/, sends www.chronotif.com to the apex domain, and answers Range requests
// (Safari won't play a video whose server ignores them; static assets alone return 200).
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.hostname === "www.chronotif.com") {
      url.hostname = "chronotif.com";
      return Response.redirect(url.toString(), 301);
    }
    const response = await env.ASSETS.fetch(request);
    const range = request.headers.get("Range");
    if (!range || response.status !== 200 || !url.pathname.startsWith("/video/")) {
      return response;
    }
    return serveRange(response, range);
  },
};

async function serveRange(response, range) {
  const body = await response.arrayBuffer();
  const size = body.byteLength;
  const match = /^bytes=(\d*)-(\d*)$/.exec(range.trim());
  let start, end;
  if (match && match[1] !== "") {
    start = Number(match[1]);
    end = match[2] === "" ? size - 1 : Math.min(Number(match[2]), size - 1);
  } else if (match && match[2] !== "") {
    start = Math.max(size - Number(match[2]), 0);
    end = size - 1;
  }
  const headers = new Headers(response.headers);
  headers.set("Accept-Ranges", "bytes");
  if (start === undefined || start > end || start >= size) {
    headers.set("Content-Range", `bytes */${size}`);
    return new Response(null, { status: 416, headers });
  }
  headers.set("Content-Range", `bytes ${start}-${end}/${size}`);
  headers.set("Content-Length", String(end - start + 1));
  return new Response(body.slice(start, end + 1), { status: 206, headers });
}
