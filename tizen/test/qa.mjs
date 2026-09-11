/*
 * A QA pass over the Tizen app: every screen, driven by the remote, against a
 * portal that answers like a real one. Each check prints PASS or FAIL with what
 * it actually saw, so a failure is a report and not a mystery.
 */
import { chromium } from "playwright-core";
import fs from "node:fs/promises";

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
  if (action === "get_short_epg") {
    // A real portal base64s its titles, and dates them in seconds.
    const now = Math.floor(Date.now() / 1000), half = 1800;
    const b64 = (t) => Buffer.from(t, "utf8").toString("base64");
    return route.fulfill(json({ epg_listings: [
      { title: b64("מהדורת החדשות"), description: b64("מה קרה היום"),
        start_timestamp: now - half, stop_timestamp: now + half },
      { title: b64("סרט הערב"), description: b64("סרט"),
        start_timestamp: now + half, stop_timestamp: now + half * 4 },
    ] }));
  }
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
  if (action === "get_vod_info") return route.fulfill(json({
    info: {
      plot: "סרט על אנשים שעושים דברים, ואז דברים אחרים קורים להם.",
      genre: "דרמה, מתח", releasedate: "2024-03-01", duration: "112",
      rating: "7.4", cast: "שחקן א, שחקנית ב, שחקן ג",
      movie_image: "http://ilvips.com:80/art/poster/9/%D7%A1%D7%A8%D7%98",
      backdrop_path: ["http://ilvips.com:80/art/wide/9/%D7%A1%D7%A8%D7%98"],
    },
  }));
  if (action === "get_series_info") return route.fulfill(json({
    info: {
      plot: "עובדים שעברו הליך שמפריד בין הזיכרונות שלהם בעבודה לחיים שבחוץ.",
      genre: "מותחן", releaseDate: "2022-02-18", rating: "8.7",
      cast: "אדם סקוט, בריט לואר",
      backdrop_path: ["http://ilvips.com:80/art/wide/4/%D7%A1%D7%93%D7%A8%D7%94"],
    },
    episodes: { 1: Array.from({ length: 10 }, (_, i) => ({
      id: 9000 + i, episode_num: i + 1, title: `ניתוק - S01E0${i + 1} - פרק`, container_extension: "mkv",
    })) },
  }));
  return route.fulfill(json([]));
});
await page.route("**/live/**", (r) => r.abort());
await page.route("**/movie/**", (r) => r.abort());
await page.route("**/series/**", (r) => r.abort());

/**
 * A picture of whatever is on the screen, when asked for one.
 *
 * The Tizen app has the same problem the Android one had: it is looked at on a
 * television in someone's front room and nowhere else, so a screen that comes
 * out wrong stays wrong until a photograph arrives. This walk already drives
 * every screen; saving what it sees costs nothing and makes them visible.
 * Set QA_SHOTS to a directory to collect them.
 */
const SHOTS = process.env.QA_SHOTS || "";
if (SHOTS) await fs.mkdir(SHOTS, { recursive: true });
const shot = async (name) => {
  if (!SHOTS) return;
  await page.screenshot({ path: `${SHOTS}/${name}.png` });
};

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
check("the portal loads", await shown("#chooseScreen"), `${Date.now() - t0} ms`);
check("the catalogue is counted out loud",
  /\d+ ערוצים · \d+ סרטים · \d+ סדרות/.test(await page.textContent("#catalogSummary")),
  await page.textContent("#catalogSummary"));

// ---- the door ---------------------------------------------------------------
check("the app opens on the choice between the two worlds", await shown("#chooseScreen"));
check("each world says how much is in it",
  /\d/.test(await page.textContent("#worldLiveCount")) && /\d/.test(await page.textContent("#worldVodCount")),
  `${await page.textContent("#worldLiveCount")} / ${await page.textContent("#worldVodCount")}`);
check("nothing of a world is on screen before one is chosen",
  !(await shown("#homeScreen")) && !(await shown("#liveScreen")) && !(await shown("#navVod")));

// ---- the television world ---------------------------------------------------
await page.click("#worldLive");
await page.waitForTimeout(1000);
check("choosing television opens the guide", await shown("#liveScreen"));
check("the library's sections are not offered here",
  !(await shown("#navVod")) && !(await shown("#navSeries")));
check("the guide draws a window, not the catalogue",
  (await page.locator('[data-nav="item"]').count()) === 20,
  `${await page.locator('[data-nav="item"]').count()} tiles for ${N_LIVE} channels`);
check("the guide says what is on now", await shown("#nowNext") &&
  (await page.textContent("#nnNow")).includes("מהדורת החדשות"),
  await page.textContent("#nnNow"));
check("and what follows it", (await page.textContent("#nnNext")).includes("סרט הערב"),
  await page.textContent("#nnNext"));
await shot("1-guide");
check("how far into the programme is drawn",
  parseFloat(await page.evaluate(() => document.querySelector("#nnFill").style.width)) > 10);
await page.keyboard.press("ArrowLeft");
await page.waitForTimeout(600);
check("moving across the guide keeps the strip filled",
  (await page.textContent("#nnNow")).includes("מהדורת החדשות"));

await fill("#search", "ספורט");
await page.waitForTimeout(500);
const sportCount = await page.locator('[data-nav="item"]').count();
check("search narrows the guide", sportCount > 0 && sportCount <= 20, `${sportCount} tiles`);
await fill("#search", "");
await page.waitForTimeout(400);

await page.locator('[data-nav="item"]').first().click();
await page.waitForTimeout(1200);
check("a channel plays at once, without a page in between", await shown("#playerScreen"));
await shot("2-player");
check("nothing of the app is left over the picture",
  !(await shown(".topbar")) && !(await shown("#browseScreen")));
check("a channel says it is live", await shown("#ovBadge"));
check("a channel has no timeline to scrub", !(await shown("#scrubRow")));
check("the picture is captioned with the programme, not the group",
  (await page.textContent("#itemMeta")).includes("עכשיו · מהדורת החדשות"),
  await page.textContent("#itemMeta"));

// Looking for the next thing while the current thing carries on.
await page.keyboard.press("ArrowLeft");
await page.waitForTimeout(700);
check("sideways over a channel opens the list of what else is on",
  await shown("#watchList"));
check("the picture moves aside rather than away",
  await page.evaluate(() => document.body.classList.contains("watching")) &&
    await shown("#playerScreen"));
const watchRows = await page.locator('[data-nav="watchItem"]').count();
check("the list beside the picture has channels in it", watchRows > 0, `${watchRows} rows`);
await shot("3-while-watching");
check("each channel says what is on it",
  (await page.textContent('[data-nav="watchItem"] .watchNow')).length > 0,
  await page.textContent('[data-nav="watchItem"] .watchNow'));

await page.keyboard.press("ArrowDown");
await page.keyboard.press("Enter");
await page.waitForTimeout(900);
check("choosing from the list changes channel and leaves the list up",
  await shown("#watchList") && await shown("#playerScreen"));

await page.keyboard.press("Backspace");
await page.waitForTimeout(500);
check("Back closes the list before it leaves the picture",
  !(await shown("#watchList")) && await shown("#playerScreen"));
check("the picture goes back to filling the screen",
  !(await page.evaluate(() => document.body.classList.contains("watching"))));

await page.keyboard.press("Backspace");
await page.waitForTimeout(500);
check("Back returns to the guide", await shown("#liveScreen") && !(await shown("#playerScreen")));
await page.keyboard.press("Backspace");
await page.waitForTimeout(500);
check("Back again returns to the door", await shown("#chooseScreen"));

// ---- the library world ------------------------------------------------------
await page.click("#worldVod");
await page.waitForTimeout(1000);
check("choosing the library opens its home", await shown("#homeScreen"));
const rows = await page.locator("#homeRows .cardRowTitle").allTextContents();
check("the home screen opens on content", rows.length > 0, rows.join(" | "));
check("no channels are mixed into the library",
  rows.every((r) => !liveCats.includes(r.split(" ")[0])), rows.join(" | "));
check("only a window of rows is built", (await page.locator("#homeRows .cardRow").count()) <= 3);
check("cards carry artwork", (await page.locator("#homeRows .cardRowTrack img").count()) > 0,
  `${await page.locator("#homeRows .cardRowTrack img").count()} images`);
check("the television's guide is not offered here", !(await shown("#navLive")));

await page.keyboard.press("f");
await page.waitForTimeout(300);
check("the yellow key marks a favourite",
  (await page.locator("#homeRows .cardRowTitle").allTextContents()).some((t) => t.includes("המועדפים")));
await page.keyboard.press("f");
await page.waitForTimeout(300);

// ---- a title -----------------------------------------------------------------
await page.click("#navSeries");
await page.waitForTimeout(900);
const seriesRows = await page.locator("#vodRows .vodRowTitle").allTextContents();
check("series are their own catalogue", seriesRows.every((r) => vodCats.every((c) => !r.includes(c))),
  seriesRows.join(" | "));
await page.locator('[data-nav="vodCard"]').first().click();
await page.waitForTimeout(1400);
check("a poster opens the title, it does not start playing", await shown("#detailScreen") && !(await shown("#playerScreen")));
check("the title carries its story", (await page.textContent("#detailPlot")).length > 20,
  (await page.textContent("#detailPlot")).slice(0, 40) + "…");
check("the title carries its facts", /\d{4}/.test(await page.textContent("#detailFacts")),
  await page.textContent("#detailFacts"));
check("a series offers its seasons", await shown("#seasonStrip"));
check("a series lists its episodes", (await page.locator('[data-nav="episode"]').count()) > 0,
  `${await page.locator('[data-nav="episode"]').count()} episodes`);
check("the first thing offered is watching it",
  (await page.textContent("#detailPlay")).includes("צפה"), await page.textContent("#detailPlay"));

await page.click("#detailFavorite");
await page.waitForTimeout(300);
check("a title can be marked from its own page",
  (await page.textContent("#detailFavorite")).includes("הסר"), await page.textContent("#detailFavorite"));
await page.click("#detailFavorite");
await page.waitForTimeout(200);

await page.locator('[data-nav="episode"]').first().click();
await page.waitForTimeout(1200);
check("an episode plays from the title's page", await shown("#playerScreen"));
check("the controls are there without hunting for them",
  (await page.locator("#controlRow .ctrlBtn:visible").count()) >= 6,
  `${await page.locator("#controlRow .ctrlBtn:visible").count()} buttons`);
check("an episode offers the next one", (await page.locator('[data-act="nextEp"]:visible').count()) === 1);

// The controls are shapes, not words — and a shape nobody can name is worse
// than a word, so the focused one still says what it is.
const drawn = await page.locator("#controlRow .ctrlBtn:visible svg").count();
const buttons = await page.locator("#controlRow .ctrlBtn:visible").count();
check("every control is drawn, not spelled out", drawn === buttons,
  `${drawn} icons for ${buttons} buttons`);
check("an unfocused control keeps its name out of sight",
  (await page.evaluate(() => {
    const el = document.querySelector("#controlRow .ctrlBtn:not(.focused) .ctrlName");
    return el ? getComputedStyle(el).opacity : "none";
  })) === "0");

// Down is the way to the controls, and the one under the cursor says its name.
await page.keyboard.press("ArrowDown");
await page.waitForTimeout(500);
check("but the focused one names itself",
  (await page.evaluate(() => {
    const el = document.querySelector("#controlRow .ctrlBtn.focused .ctrlName");
    return el ? getComputedStyle(el).opacity : "none";
  })) === "1",
  await page.evaluate(() => {
    const el = document.querySelector("#controlRow .ctrlBtn.focused .ctrlName");
    return el ? el.textContent : "nothing focused";
  }));

// Play and pause are the same button wearing two faces; writing text into it
// would have thrown the face away.
const faceOf = () => page.evaluate(() =>
  document.querySelector("#toggleIcon").innerHTML.replace(/\s+/g, ""));
const pausedFace = await faceOf();
const pausedName = await page.getAttribute("#btnToggle", "aria-label");
await page.click("#btnToggle");
await page.waitForTimeout(400);
check("the play button changes its face, and keeps having one",
  (await faceOf()) !== pausedFace && (await faceOf()).length > 0,
  `${pausedName} -> ${await page.getAttribute("#btnToggle", "aria-label")}`);
check("and the name follows the face",
  (await page.getAttribute("#btnToggle", "aria-label")) !== pausedName);
await page.click("#btnToggle");
await page.waitForTimeout(400);
// Opening the controls above changed where the remote is pointing; put it back
// where the rest of the walk expects to find it.
await page.keyboard.press("Backspace");
await page.waitForTimeout(500);
check("closing the controls stays in the player, it does not leave it",
  await shown("#playerScreen"));

await page.evaluate(() => { const a = window.__talohim; a.state.duration = 3600; a.state.position = 600; a.renderProgress(); });
await page.keyboard.press("ArrowLeft");
await page.waitForTimeout(700);
const back = await page.evaluate(() => Math.round(window.__talohim.state.position));
check("left goes back ten seconds", back === 590, `${back}s`);
await page.keyboard.press("ArrowRight");
await page.waitForTimeout(700);
const forward = await page.evaluate(() => Math.round(window.__talohim.state.position));
check("right goes forward ten seconds", forward === 600, `${forward}s`);

await page.keyboard.press("Backspace");
await page.waitForTimeout(500);
check("Back from playback returns to the title", await shown("#detailScreen"));
await page.keyboard.press("Backspace");
await page.waitForTimeout(500);
check("Back from the title returns to the catalogue", await shown("#vodScreen"));

// ---- what has been seen, and what to watch next ----------------------------
await page.locator('[data-nav="vodCard"]').first().click();
await page.waitForTimeout(1200);
const markLabel = await page.textContent("#detailWatched");
check("a series is ticked off by the season, not as one lump",
  markLabel.includes("העונה"), markLabel);
await page.click("#detailWatched");
await page.waitForTimeout(400);
check("ticking a whole season ticks every episode",
  (await page.locator(".episodeCard.seen").count()) > 0,
  `${await page.locator(".episodeCard.seen").count()} episodes marked`);
check("and the button now offers to untick it",
  /לא נצפתה|לא נצפה/.test(await page.textContent("#detailWatched")),
  await page.textContent("#detailWatched"));
await page.click("#detailWatched");
await page.waitForTimeout(400);
check("unticking clears them again", (await page.locator(".episodeCard.seen").count()) === 0);

// Watch one episode to the end, then look at what the home screen makes of it.
await page.evaluate(() => {
  const now = Date.now();
  localStorage.setItem("talohimWatchedV1", JSON.stringify([]));
  // Two finished films in two different categories, so the taste has something
  // to be a taste about.
  localStorage.setItem("talohimHistoryV1", JSON.stringify([
    { id: "v5000", at: now, position: 5900, duration: 6000,
      card: { id: "v5000", name: "סרט 1", group: "חדש בקולנוע", kind: "VOD", contentType: "MOVIE" } },
    { id: "v5001", at: now - 60000, position: 5900, duration: 6000,
      card: { id: "v5001", name: "סרט 2", group: "אקשן", kind: "VOD", contentType: "MOVIE" } },
  ]));
});
await page.reload();
await page.waitForTimeout(2600);
await page.click("#worldVod");
await page.waitForTimeout(1400);
const tasteRows = await page.locator("#homeRows .cardRowTitle").allTextContents();
check("the home screen suggests what to watch next",
  tasteRows.some((r) => r.includes("מומלץ בשבילך")), tasteRows.join(" | "));
check("and says which title it is reasoning from",
  tasteRows.some((r) => r.includes("כי צפית ב")), tasteRows.join(" | "));
const suggested = await page.locator('#homeRows .cardRow').first()
  .locator('.cardName').allTextContents();
const named = await page.locator('#homeRows .cardRow').nth(1)
  .locator('.cardName').allTextContents();
check("the two suggestion rows do not offer the same titles twice",
  suggested.every((t) => !named.includes(t)),
  `${suggested.length} suggested, ${named.length} named`);
check("what was finished is not offered as unfinished",
  !tasteRows.some((r) => r.includes("המשך לצפות")), tasteRows.join(" | "));
check("a finished title is ticked on its card",
  (await page.locator("#homeRows .seenTick").count()) > 0,
  `${await page.locator("#homeRows .seenTick").count()} ticks`);

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
check("the original name finds it too",
  (await page.locator('[data-nav="searchCard"]').allTextContents()).some((t) => t.includes("ניתוק")));
await fill("#globalSearch", "breaking bad");
await page.waitForTimeout(600);
check("a name spelled in Hebrew letters is found in English",
  (await page.locator('[data-nav="searchCard"]').allTextContents()).some((t) => t.includes("ברייקינג")));
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
check("a saved source signs itself in", await shown("#chooseScreen"));
await page.click("#worldVod");
await page.waitForTimeout(900);
const afterRows = await page.locator("#homeRows .cardRowTitle").allTextContents();
check("what was being watched comes back", afterRows.some((r) => r.includes("המשך לצפות")), afterRows.join(" | "));
check("how far in is drawn on the card", (await page.locator(".cardProgress").count()) > 0);

// ---- the television's own player ------------------------------------------
//
// Everything above runs in a browser, where webapis does not exist and the app
// quietly falls back to a <video> tag. That means the code that actually runs
// on the television — AVPlay, and its state machine — was never once exercised
// here. This is that state machine, with its real rule: open() is legal only
// from NONE, and stop() walks back no further than IDLE.
await page.addInitScript(() => {
  const av = {
    state: "NONE",
    opened: [],
    refused: 0,
    listener: null,
    getState() { return this.state; },
    open(url) {
      if (this.state !== "NONE") {
        this.refused += 1;
        const e = new Error("PLAYER_ERROR_INVALID_STATE");
        e.name = "InvalidAccessError";
        throw e;
      }
      this.opened.push(url);
      this.state = "IDLE";
    },
    close() {
      if (this.state === "NONE") throw new Error("InvalidAccessError");
      this.state = "NONE";
    },
    stop() {
      if (this.state === "NONE") throw new Error("InvalidAccessError");
      this.state = "IDLE";
    },
    prepareAsync(ok, fail) {
      if (this.state !== "IDLE") { fail("PLAYER_ERROR_INVALID_STATE"); return; }
      this.state = "READY";
      setTimeout(ok, 10);
    },
    play() { this.state = "PLAYING"; },
    pause() { this.state = "PAUSED"; },
    seekTo() {},
    getDuration() { return 3600000; },
    getCurrentTime() { return 0; },
    setListener(l) { this.listener = l; },
    setDisplayRect() {},
    setDisplayMethod() {},
    setStreamingProperty() {},
    setSilentSubtitle() {},
  };
  window.webapis = Object.assign(window.webapis || {}, { avplay: av });
});
await page.reload();
await page.waitForTimeout(2600);
await page.click("#worldLive");
await page.waitForTimeout(1200);

const avState = () => page.evaluate(() => ({
  state: webapis.avplay.state,
  opened: webapis.avplay.opened.length,
  refused: webapis.avplay.refused,
  message: (document.querySelector("#playerMessage") || {}).textContent || "",
}));

await page.locator('[data-nav="item"]').first().click();
await page.waitForTimeout(900);
const first = await avState();
check("the television's player starts a channel", first.state === "PLAYING",
  `state ${first.state}, ${first.opened} opened`);

// The one that mattered: a second stream, after the first was stopped.
await page.keyboard.press("Backspace");
await page.waitForTimeout(500);
await page.locator('[data-nav="item"]').nth(1).click();
await page.waitForTimeout(900);
const second = await avState();
check("and a second one after it, which is where it used to break",
  second.state === "PLAYING" && second.refused === 0,
  `state ${second.state}, ${second.opened} opened, ${second.refused} refused` +
    (second.message ? `, said "${second.message}"` : ""));
check("no address is refused for being opened out of turn", second.refused === 0);

check("no page errors anywhere", errors.length === 0, errors.slice(0, 3).join(" | "));

console.log("\n" + "-".repeat(60));
const failed = results.filter((r) => !r.ok);
console.log(`${results.length - failed.length}/${results.length} passed`);
if (failed.length) console.log("failed:\n  " + failed.map((f) => f.name + (f.detail ? " — " + f.detail : "")).join("\n  "));
await browser.close();
process.exit(failed.length ? 1 : 0);
