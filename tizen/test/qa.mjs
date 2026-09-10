/*
 * A QA pass over the Tizen app: every screen, driven by the remote, against a
 * portal that answers like a real one. Each check prints PASS or FAIL with what
 * it actually saw, so a failure is a report and not a mystery.
 */
import { chromium } from "playwright-core";

const results = [];
const check = (name, ok, detail = "") => {
  results.push({ name, ok, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`);
};

const N_LIVE = 2200, N_VOD = 1500, N_SERIES = 320;
const liveCats = ["ישראל", "ספורט", "חדשות", "ילדים"];
const vodCats = ["חדש בקולנוע", "אקשן", "קומדיה"];
const seriesCats = ["דרמה", "Apple TV+"];
const israeli = ["כאן 11", "קשת 12", "רשת 13", "ספורט 1", "i24NEWS"];
const shows = ["ניתוק (2022)", "ברייקינג באד", "על תנועת כדור הארץ", "פאודה"];
const json = (b) => ({ status: 200, contentType: "application/json; charset=utf-8", body: JSON.stringify(b) });

const art = (kind, seed, label) => ({
  status: 200,
  contentType: "image/svg+xml",
  body: `<svg xmlns="http://www.w3.org/2000/svg" width="${kind === "poster" ? 300 : 400}" height="${kind === "poster" ? 450 : 225}">
    <rect width="100%" height="100%" fill="#${(seed * 7919 % 0xffffff).toString(16).padStart(6, "0")}"/>
    <text x="50%" y="50%" fill="#fff" font-size="28" text-anchor="middle">${label}</text></svg>`,
});

const BROWSER = process.env.QA_BROWSER || "/opt/pw-browsers/chromium-1194/chrome-linux/chrome";
const ORIGIN = process.env.QA_ORIGIN || "http://127.0.0.1:5300";
const browser = await chromium.launch({ executablePath: BROWSER, args: ["--no-sandbox", "--no-proxy-server"] });
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
// Failures are judged by what was requested, not by a message that omits it:
// the Tizen web API file exists only on a television, and the streams here are
// deliberately refused.
// A stream request is expected to fail here: nothing is being served. That
// includes the endpoint ladder's plainest rung, which carries no /live/ in it.
const portal = /ilvips\.com|ilvip\.net/;
const expected = (url) =>
  /\$WEBAPIS|favicon/.test(url) ||
  (portal.test(url) && !/player_api\.php|\/art\//.test(url));
page.on("requestfailed", (r) => { if (!expected(r.url())) errors.push("request failed: " + r.url()); });
page.on("response", (r) => {
  if (r.status() >= 400 && !expected(r.url())) errors.push(`http ${r.status()}: ${r.url()}`);
});

await page.route("**/art/**", (route) => {
  const m = /\/art\/(poster|wide)\/(\d+)\/(.*)$/.exec(route.request().url());
  return m ? route.fulfill(art(m[1], Number(m[2]), decodeURIComponent(m[3]).slice(0, 14))) : route.abort();
});
await page.route("**/player_api.php*", (route) => {
  const action = new URL(route.request().url()).searchParams.get("action");
  if (!action) return route.fulfill(json({ user_info: { auth: 1, status: "Active" } }));
  if (action === "get_live_categories") return route.fulfill(json(liveCats.map((c, i) => ({ category_id: i + 1, category_name: c }))));
  if (action === "get_live_streams") return route.fulfill(json(Array.from({ length: N_LIVE }, (_, i) => ({
    stream_id: 1000 + i, name: i < israeli.length ? israeli[i] : `ערוץ ${i + 1} HD`,
    category_id: (i % liveCats.length) + 1,
    stream_icon: `http://ilvips.com:80/art/wide/${i}/${encodeURIComponent("ערוץ")}`,
  }))));
  if (action === "get_vod_categories") return route.fulfill(json(vodCats.map((c, i) => ({ category_id: 50 + i, category_name: c }))));
  if (action === "get_vod_streams") return route.fulfill(json(Array.from({ length: N_VOD }, (_, i) => ({
    stream_id: 5000 + i, name: `סרט ${i + 1}`, container_extension: "mkv", category_id: 50 + (i % vodCats.length),
    stream_icon: `http://ilvips.com:80/art/poster/${i}/${encodeURIComponent("סרט")}`,
  }))));
  if (action === "get_series_categories") return route.fulfill(json(seriesCats.map((c, i) => ({ category_id: 90 + i, category_name: c }))));
  if (action === "get_series") return route.fulfill(json(Array.from({ length: N_SERIES }, (_, i) => ({
    series_id: 700 + i, name: i < shows.length ? shows[i] : `סדרה ${i + 1}`,
    category_id: 90 + (i % seriesCats.length),
    o_name: i === 0 ? "Severance" : undefined,
    cover: `http://ilvips.com:80/art/poster/${i + 3}/${encodeURIComponent("סדרה")}`,
  }))));
  if (action === "get_series_info") return route.fulfill(json({
    episodes: { 1: Array.from({ length: 10 }, (_, i) => ({
      id: 9000 + i, episode_num: i + 1, title: `ניתוק - S01E0${i + 1} - פרק`, container_extension: "mkv",
    })) },
  }));
  return route.fulfill(json([]));
});
await page.route("**/live/**", (r) => r.abort());
await page.route("**/movie/**", (r) => r.abort());
await page.route("**/series/**", (r) => r.abort());

const id = () => page.evaluate(() => (document.activeElement && document.activeElement.id) || "");
const shown = (sel) => page.evaluate((s) => {
  const el = document.querySelector(s);
  if (!el) return false;
  const style = getComputedStyle(el);
  return style.display !== "none" && style.visibility !== "hidden" && el.getBoundingClientRect().width > 0;
}, sel);
const fill = (sel, value) => page.evaluate(([s, v]) => {
  const el = document.querySelector(s);
  el.removeAttribute("readonly");
  el.value = v;
  el.dispatchEvent(new Event("input", { bubbles: true }));
  el.setAttribute("readonly", "readonly");
}, [sel, value]);

// ---- setup ------------------------------------------------------------------
await page.goto(ORIGIN + "/index.html?setup=1&test=1", { waitUntil: "load" });
await page.waitForTimeout(1700);
check("the setup screen opens", await shown("#setupScreen"));
await page.click(".sourceTab[data-source='xtream']");
await page.waitForTimeout(300);

const panel = await page.evaluate(() => {
  const p = document.querySelector(".setupPanel").getBoundingClientRect();
  const b = document.querySelector("#loadXtream").getBoundingClientRect();
  return { bottom: Math.round(p.bottom), button: Math.round(b.bottom) };
});
check("the form fits the screen", panel.button <= 1080 && panel.bottom <= 1080, `button ends at ${panel.button}px`);

check("fields are read-only until asked", await page.evaluate(() =>
  [...document.querySelectorAll("#xtreamForm input[type=text], #xtreamForm input[type=password]")]
    .every((el) => el.hasAttribute("readonly"))));

await page.evaluate(() => document.querySelector("#xtServer").focus());
await page.keyboard.press("Enter");
await page.waitForTimeout(150);
check("OK opens a field for typing", !(await page.evaluate(() => document.querySelector("#xtServer").hasAttribute("readonly"))));
await page.keyboard.type("http://ilvips.com:80");
await page.keyboard.press("Escape");
await page.waitForTimeout(250);
check("closing the keyboard hands over the next field", (await id()) === "xtUser", `focus: ${await id()}`);
check("what was typed is kept", (await page.inputValue("#xtServer")) === "http://ilvips.com:80");

await fill("#xtUser", "demo");
await fill("#xtPass", "demo");
const t0 = Date.now();
await page.click("#loadXtream");
await page.waitForTimeout(2600);
check("the portal loads", await shown("#homeScreen"), `${Date.now() - t0} ms`);
check("the catalogue is counted out loud",
  /\d+ ערוצים · \d+ סרטים · \d+ סדרות/.test(await page.textContent("#catalogSummary")),
  await page.textContent("#catalogSummary"));

// ---- home -------------------------------------------------------------------
const rows = await page.locator("#homeRows .cardRowTitle").allTextContents();
check("the home screen opens on content", rows.length > 0, rows.join(" | "));
check("only a window of rows is built", await page.locator("#homeRows .cardRow").count() <= 3,
  `${await page.locator("#homeRows .cardRow").count()} rows in the DOM`);
check("cards carry artwork", await page.locator("#homeRows .cardRowTrack img").count() > 0,
  `${await page.locator("#homeRows .cardRowTrack img").count()} images`);

await page.keyboard.press("ArrowDown");
await page.waitForTimeout(200);
check("the hero follows the highlight", (await page.textContent("#homeTitle")).length > 0, await page.textContent("#homeTitle"));

await page.keyboard.press("f");
await page.waitForTimeout(300);
check("the yellow key marks a favourite",
  (await page.locator("#homeRows .cardRowTitle").allTextContents()).some((t) => t.includes("המועדפים")),
  (await page.locator("#homeRows .cardRowTitle").allTextContents()).join(" | "));
await page.keyboard.press("f");
await page.waitForTimeout(300);

// ---- live -------------------------------------------------------------------
await page.click("#navLive");
await page.waitForTimeout(900);
check("the guide draws a window, not the catalogue",
  (await page.locator('[data-nav="item"]').count()) === 20,
  `${await page.locator('[data-nav="item"]').count()} tiles for ${N_LIVE} channels`);
await fill("#search", "ספורט");
await page.waitForTimeout(500);
const sportCount = await page.locator('[data-nav="item"]').count();
check("search narrows the guide", sportCount > 0 && sportCount <= 20, `${sportCount} tiles`);
await fill("#search", "");
await page.waitForTimeout(400);

await page.locator('[data-nav="item"]').first().click();
await page.waitForTimeout(1200);
check("a channel takes the whole screen", await shown("#playerScreen"));
check("nothing of the app is left over the picture",
  !(await shown(".topbar")) && !(await shown("#browseScreen")));
check("a channel says it is live", await shown("#ovBadge"));
check("a channel has no timeline to scrub", !(await shown("#scrubRow")));
await page.keyboard.press("Backspace");
await page.waitForTimeout(500);
check("Back returns to the guide", await shown("#liveScreen") && !(await shown("#playerScreen")));

// ---- films and series -------------------------------------------------------
await page.click("#navVod");
await page.waitForTimeout(900);
const movieRows = await page.locator("#vodRows .vodRowTitle").allTextContents();
check("films are their own catalogue", movieRows.every((r) => !r.includes("דרמה") && !r.includes("Apple")),
  movieRows.join(" | "));
await page.click("#navSeries");
await page.waitForTimeout(900);
const seriesRows = await page.locator("#vodRows .vodRowTitle").allTextContents();
check("series are their own catalogue", seriesRows.every((r) => vodCats.every((c) => !r.includes(c))),
  seriesRows.join(" | "));

await page.locator('[data-nav="vodCard"]').first().click();
await page.waitForTimeout(1300);
const episodes = await page.locator('[data-nav="vodCard"]').allTextContents();
check("a series opens on its episodes", episodes.length > 0, episodes.slice(0, 2).join(" / "));
check("the portal's own numbering is not doubled",
  episodes.every((e) => !/S\d+E\d+ · .*S\d+E\d+/i.test(e)), episodes[0]);

await page.locator('[data-nav="vodCard"]').first().click();
await page.waitForTimeout(1200);
check("an episode opens the player", await shown("#playerScreen"));
check("the controls are there without hunting for them",
  (await page.locator("#controlRow .ctrlBtn:visible").count()) >= 6,
  `${await page.locator("#controlRow .ctrlBtn:visible").count()} buttons`);
check("an episode offers the next one",
  await page.locator('[data-act="nextEp"]:visible').count() === 1);

await page.evaluate(() => { const a = window.__talohim; a.state.duration = 3600; a.state.position = 600; a.renderProgress(); });
await page.keyboard.press("ArrowLeft");
await page.waitForTimeout(700);
const back = await page.evaluate(() => Math.round(window.__talohim.state.position));
check("left goes back ten seconds", back === 590, `${back}s`);
await page.keyboard.press("ArrowRight");
await page.waitForTimeout(700);
const forward = await page.evaluate(() => Math.round(window.__talohim.state.position));
check("right goes forward ten seconds", forward === 600, `${forward}s`);

await page.keyboard.press("ArrowDown");
await page.waitForTimeout(300);
check("down reaches the buttons", await page.evaluate(() => !!(window.__talohim.state.focusEl || {}).dataset?.act));
await page.keyboard.press("Backspace");
await page.waitForTimeout(200);
check("Back leaves the buttons before the film", await shown("#playerScreen"));
await page.keyboard.press("Backspace");
await page.waitForTimeout(400);
check("Back again leaves the film", !(await shown("#playerScreen")));

// ---- search -----------------------------------------------------------------
await page.click("#navSearch");
await page.waitForTimeout(500);
await fill("#globalSearch", "ניתוק");
await page.waitForTimeout(600);
const hebrew = await page.locator('[data-nav="searchCard"]').allTextContents();
check("a Hebrew name finds itself and nothing else",
  hebrew.length === 1 && hebrew[0].includes("ניתוק"), hebrew.join(" / "));
await fill("#globalSearch", "severance");
await page.waitForTimeout(600);
const english = await page.locator('[data-nav="searchCard"]').allTextContents();
check("the original name finds it too", english.some((t) => t.includes("ניתוק")), english.join(" / "));
await fill("#globalSearch", "breaking bad");
await page.waitForTimeout(600);
const translit = await page.locator('[data-nav="searchCard"]').allTextContents();
check("a name spelled in Hebrew letters is found in English",
  translit.some((t) => t.includes("ברייקינג")), translit.join(" / "));
await fill("#globalSearch", "i24");
await page.waitForTimeout(600);
const noise = await page.locator('[data-nav="searchCard"]').count();
check("a short query does not flood", noise <= 3, `${noise} results`);

// ---- memory -----------------------------------------------------------------
await page.evaluate(() => {
  const raw = JSON.parse(localStorage.getItem("talohimHistoryV1") || "[]");
  if (raw[0]) { raw[0].position = 900; raw[0].duration = 3600; }
  localStorage.setItem("talohimHistoryV1", JSON.stringify(raw));
});
await page.reload({ waitUntil: "load" });
await page.waitForTimeout(3000);
check("a saved source signs itself in", await shown("#homeScreen"));
const afterRows = await page.locator("#homeRows .cardRowTitle").allTextContents();
check("what was being watched comes back", afterRows.some((r) => r.includes("המשך לצפות")), afterRows.join(" | "));
check("how far in is drawn on the card", (await page.locator(".cardProgress").count()) > 0);

check("no page errors anywhere", errors.length === 0, errors.slice(0, 3).join(" | "));

console.log("\n" + "-".repeat(60));
const failed = results.filter((r) => !r.ok);
console.log(`${results.length - failed.length}/${results.length} passed`);
if (failed.length) console.log("failed:\n  " + failed.map((f) => f.name + (f.detail ? " — " + f.detail : "")).join("\n  "));
await browser.close();
process.exit(failed.length ? 1 : 0);
