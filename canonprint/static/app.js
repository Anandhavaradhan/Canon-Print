/* Canon Print - front end. Plain JavaScript, no build step. */
"use strict";

const TOKEN = document.querySelector('meta[name="token"]').content;
const SAVE_AS_PDF = "__save_as_pdf__";
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

const state = {
  settings: {},
  defaults: {},
  printer: null,
  printers: [],
  files: [],
  presets: [],
  history: [],
  look: { textsize: "100", theme: "auto", motion: false, speak: false },
  preview: null,
  idx: 0,
  side: "front",
  previewSeq: 0,
  uploading: 0,
  platform: "",
};

const BUILTIN_PRESETS = [
  { id: "b-everyday", name: "Everyday B&W", settings: { sides: "one", pages_per_side: 1, booklet: false, color: "bw", quality: "standard", paper_type: "plain" } },
  { id: "b-book", name: "Both sides, like a book", settings: { sides: "long", pages_per_side: 1, booklet: false, paper_type: "plain" } },
  { id: "b-handout", name: "2 per side, both sides", settings: { sides: "short", pages_per_side: 2, booklet: false, borders: true, paper_type: "plain" } },
  { id: "b-ink", name: "Save ink", settings: { color: "bw", quality: "draft", paper_type: "plain" } },
  { id: "b-photo", name: "Photo", settings: { sides: "one", pages_per_side: 1, booklet: false, color: "color", quality: "high", paper_type: "photo_glossy", scaling: "fit" } },
];

/* ------------------------------------------------------------------ API */

async function api(method, path, body, extraHeaders = {}) {
  const opts = { method, headers: { "X-Token": TOKEN, ...extraHeaders } };
  if (body instanceof Blob || body instanceof ArrayBuffer) {
    opts.body = body;
  } else if (body !== undefined) {
    opts.body = JSON.stringify(body);
    opts.headers["Content-Type"] = "application/json";
  }
  let res;
  try {
    res = await fetch("/api/" + path, opts);
  } catch {
    throw Object.assign(new Error("Canon Print has stopped. Start it again, then reload this page."), { code: "offline" });
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw Object.assign(new Error(data.error || "Something went wrong."), { code: data.code });
  return data;
}

const imgUrl = (path, w) => `${path}?w=${Math.round(w)}&t=${encodeURIComponent(TOKEN)}`;

/* ------------------------------------------------------------------ small helpers */

function debounce(fn, ms) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}

function announce(text) {
  const el = $("#announcer");
  el.textContent = "";
  setTimeout(() => (el.textContent = text), 30);
}

function toast(message, { error = false, action = null, timeout = 6000 } = {}) {
  const el = document.createElement("div");
  el.className = "toast" + (error ? " error" : "");
  el.setAttribute("role", error ? "alert" : "status");
  const msg = document.createElement("span");
  msg.className = "toast-msg";
  msg.textContent = message;
  el.append(msg);
  if (action) {
    const b = document.createElement("button");
    b.type = "button";
    b.textContent = action.label;
    b.addEventListener("click", () => { action.run(); el.remove(); });
    el.append(b);
  }
  const close = document.createElement("button");
  close.type = "button";
  close.setAttribute("aria-label", "Dismiss");
  close.textContent = "✕";
  close.addEventListener("click", () => el.remove());
  el.append(close);
  $("#toasts").append(el);
  if (timeout) setTimeout(() => el.remove(), error ? timeout * 2 : timeout);
}

function plural(n, word, many = word + "s") { return `${n} ${n === 1 ? word : many}`; }

function pagesText(list) {
  if (!list || !list.length) return "blank";
  if (list.length === 1) return `page ${list[0]}`;
  const sorted = [...list];
  const consecutive = sorted.every((v, i) => i === 0 || v === sorted[i - 1] + 1);
  if (consecutive && list.length > 2) return `pages ${list[0]}–${list[list.length - 1]}`;
  return `pages ${list.slice(0, -1).join(", ")} and ${list[list.length - 1]}`;
}

function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") e.className = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else if (v === true) e.setAttribute(k, "");
    else if (v !== false && v != null) e.setAttribute(k, v);
  }
  for (const c of children.flat()) if (c != null) e.append(c);
  return e;
}

function icon(name) {
  const s = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  s.setAttribute("aria-hidden", "true");
  const u = document.createElementNS("http://www.w3.org/2000/svg", "use");
  u.setAttribute("href", "#i-" + name);
  s.append(u);
  return s;
}

/* ------------------------------------------------------------------ look & feel */

function applyLook() {
  const L = state.look;
  document.documentElement.style.setProperty("--scale", (Number(L.textsize) || 100) / 100);
  if (L.theme && L.theme !== "auto") document.documentElement.dataset.theme = L.theme;
  else delete document.documentElement.dataset.theme;
  if (L.motion) document.documentElement.dataset.motion = "reduce";
  else delete document.documentElement.dataset.motion;
  $$('input[name="textsize"]').forEach((r) => (r.checked = r.value === String(L.textsize || "100")));
  $$('input[name="theme"]').forEach((r) => (r.checked = r.value === (L.theme || "auto")));
  $("#opt-motion").checked = !!L.motion;
  $("#opt-speak").checked = !!L.speak;
  requestAnimationFrame(sizeSheet);
}

const saveLook = debounce(() => api("POST", "prefs", { look: state.look }).catch(() => {}), 400);

function bindLook() {
  $$('input[name="textsize"]').forEach((r) => r.addEventListener("change", () => { state.look.textsize = r.value; applyLook(); saveLook(); }));
  $$('input[name="theme"]').forEach((r) => r.addEventListener("change", () => { state.look.theme = r.value; applyLook(); saveLook(); }));
  $("#opt-motion").addEventListener("change", (e) => { state.look.motion = e.target.checked; applyLook(); saveLook(); });
  $("#opt-speak").addEventListener("change", (e) => { state.look.speak = e.target.checked; saveLook(); });
}

/* ------------------------------------------------------------------ printers */

function renderPrinters() {
  const sel = $("#printer");
  sel.innerHTML = "";
  const real = state.printers.filter((p) => !p.virtual);
  if (!real.length) {
    sel.append(el("option", { disabled: true }, "No printers found"));
  }
  for (const p of state.printers) {
    let label = p.label;
    if (p.default && !p.virtual) label += " (default)";
    sel.append(el("option", { value: p.name }, label));
  }
  sel.value = state.printer;
  $("#printer-detail").textContent = real.length
    ? `Using: ${labelFor(state.printer)}`
    : "No printers were found. Check the printer is turned on and connected (USB or Wi-Fi), then choose “Find printers again”.";
  updatePrintLabel();
  pollStatus();
}

function labelFor(name) {
  return (state.printers.find((p) => p.name === name) || {}).label || name;
}

async function pollStatus() {
  if (!state.printer) return;
  const dot = $("#printer-dot");
  const txt = $("#printer-status");
  try {
    const s = await api("GET", "printer-status?name=" + encodeURIComponent(state.printer));
    dot.dataset.state = s.state;
    let msg = s.message;
    if (s.jobs) msg += ` · ${plural(s.jobs, "job")} waiting`;
    if (txt.textContent !== msg) txt.textContent = msg;
    txt.title = msg;
  } catch (e) {
    dot.dataset.state = "error";
    txt.textContent = e.code === "offline" ? "App stopped" : "";
  }
}

async function refreshPrinters() {
  const { printers } = await api("GET", "printers");
  state.printers = printers;
  if (!printers.some((p) => p.name === state.printer)) {
    state.printer = (printers.find((p) => p.canon) || printers.find((p) => p.default) || printers[0]).name;
  }
  renderPrinters();
  toast(`Found ${plural(printers.filter((p) => !p.virtual).length, "printer")}.`);
}

/* ------------------------------------------------------------------ files */

function renderFiles() {
  const list = $("#file-list");
  list.innerHTML = "";
  state.files.forEach((f, i) => {
    const ext = (f.name.split(".").pop() || "").slice(0, 4).toUpperCase();
    const li = el("li", { class: "file" + (f.loading ? " loading" : "") },
      el("span", { class: "file-icon", "aria-hidden": "true" }, ext || "FILE"),
      el("div", { class: "file-main" },
        el("div", { class: "file-name", title: f.name }, f.name),
        el("div", { class: "file-meta" }, f.loading ? "Opening…" : plural(f.pages, "page")),
      ),
    );
    if (!f.loading) {
      const actions = el("div", { class: "file-actions" });
      if (state.files.length > 1) {
        actions.append(
          el("button", { type: "button", class: "mini-btn", "aria-label": `Move ${f.name} up`, disabled: i === 0, onclick: () => moveFile(i, -1) }, icon("up")),
          el("button", { type: "button", class: "mini-btn", "aria-label": `Move ${f.name} down`, disabled: i === state.files.length - 1, onclick: () => moveFile(i, 1) }, icon("down")),
        );
      }
      actions.append(el("button", { type: "button", class: "mini-btn", "aria-label": `Remove ${f.name}`, onclick: () => removeFile(f) }, icon("x")));
      li.append(actions);
    }
    list.append(li);
  });
  const real = state.files.filter((f) => !f.loading);
  $("#no-files").hidden = state.files.length > 0;
  $("#drop-empty").hidden = real.length > 0;
  $("#viewer").hidden = real.length === 0;
  $("#print-btn").disabled = real.length === 0 || state.uploading > 0;
  if (!real.length) {
    state.preview = null;
    $("#thumbs").innerHTML = "";
    $("#side-toggle").hidden = true;
    $("#summary-main").textContent = state.uploading ? "Opening your document…" : "Open a document to begin.";
    $("#summary-sub").textContent = "";
  }
}

async function moveFile(i, delta) {
  const ids = state.files.map((f) => f.id);
  const [x] = ids.splice(i, 1);
  ids.splice(i + delta, 0, x);
  const { files } = await api("POST", "files/reorder", { order: ids });
  state.files = files;
  renderFiles();
  announce(`Moved ${x && files.find((f) => f.id === x).name} to position ${i + delta + 1}.`);
  const btns = $$(".file")[i + delta]?.querySelectorAll(".mini-btn");
  btns?.[delta < 0 ? 0 : 1]?.focus();
  refreshPreview();
}

async function removeFile(f) {
  const { files } = await api("DELETE", "files/" + f.id);
  state.files = files;
  renderFiles();
  announce(`Removed ${f.name}.`);
  (document.querySelector(".file .mini-btn") || $("#add-files")).focus();
  if (files.length) refreshPreview();
}

async function uploadFiles(fileList) {
  const files = [...fileList];
  if (!files.length) return;
  for (const file of files) {
    const temp = { id: "tmp-" + Math.random(), name: file.name, pages: 0, loading: true };
    state.files.push(temp);
    state.uploading++;
    renderFiles();
    announce(`Opening ${file.name}…`);
    let password = "";
    for (;;) {
      try {
        const res = await api("POST", "files", file, {
          "X-Filename": encodeURIComponent(file.name),
          "X-Password": encodeURIComponent(password),
        });
        state.files = state.files.filter((f) => f !== temp);
        const loading = state.files.filter((f) => f.loading);
        state.files = [...res.files, ...loading];
        announce(`${file.name} is ready, ${plural(res.file.pages, "page")}.`);
        break;
      } catch (e) {
        if (e.code === "password") {
          const pw = await promptDialog({
            title: "Password needed",
            label: `${file.name} is protected. Type its password to open it.`,
            type: "password",
            ok: "Open",
          });
          if (pw != null && pw !== "") { password = pw; continue; }
        } else {
          toast(e.message, { error: true });
        }
        state.files = state.files.filter((f) => f !== temp);
        break;
      }
    }
    state.uploading--;
    renderFiles();
  }
  if (state.files.some((f) => !f.loading)) refreshPreview();
}

function bindFiles() {
  const input = $("#file-input");
  const open = () => input.click();
  $("#add-files").addEventListener("click", open);
  $("#choose-files").addEventListener("click", open);
  input.addEventListener("change", () => { uploadFiles(input.files); input.value = ""; });

  let depth = 0;
  const hasFiles = (e) => [...(e.dataTransfer?.types || [])].includes("Files");
  window.addEventListener("dragenter", (e) => { if (!hasFiles(e)) return; e.preventDefault(); depth++; document.body.classList.add("dragging"); });
  window.addEventListener("dragover", (e) => { if (hasFiles(e)) e.preventDefault(); });
  window.addEventListener("dragleave", () => { depth = Math.max(0, depth - 1); if (!depth) document.body.classList.remove("dragging"); });
  window.addEventListener("drop", (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    depth = 0;
    document.body.classList.remove("dragging");
    uploadFiles(e.dataTransfer.files);
  });
}

/* ------------------------------------------------------------------ settings controls */

function renderControls() {
  const s = state.settings;
  $$('input[name="sides"]').forEach((r) => { r.checked = r.value === s.sides; r.disabled = s.booklet && r.value !== "short"; });
  const layoutVal = s.booklet ? "booklet" : String(s.pages_per_side);
  $$('input[name="layout"]').forEach((r) => (r.checked = r.value === layoutVal));
  $$('input[name="color"]').forEach((r) => (r.checked = r.value === s.color));
  $$('input[name="quality"]').forEach((r) => (r.checked = r.value === s.quality));
  $$('input[name="duplex_method"]').forEach((r) => (r.checked = r.value === s.duplex_method));
  $$("[data-setting]").forEach((input) => {
    const key = input.dataset.setting;
    const v = s[key];
    if (input.type === "checkbox") input.checked = !!v;
    else if (document.activeElement !== input) input.value = v ?? "";
  });
  $("#pages_per_side").disabled = s.booklet;
  $("#scale-field").hidden = s.scaling !== "custom";
  $("#margin_mm").value = String(Number(s.margin_mm));
  if (!$("#margin_mm").value) $("#margin_mm").value = "5";

  // Context notes for sides
  const note = $("#sides-note");
  let text = "";
  if (s.booklet) text = "Booklets always print on both sides. Fold the printed pile in half to make your booklet.";
  else if (s.sides !== "one" && s.pages_per_side === 2)
    text = "With 2 pages per side the paper is sideways: “short edge” turns it like a book, “long edge” flips it up like a calendar.";
  else if (s.sides !== "one" && s.duplex_method === "manual")
    text = "Your printer prints the fronts first, then shows you how to put the pile back in for the backs.";
  note.hidden = !text;
  note.textContent = text;
  updatePrintLabel();
}

function updatePrintLabel() {
  const pdf = state.printer === SAVE_AS_PDF;
  $("#print-label").textContent = pdf ? "Save PDF" : "Print";
}

const persistSettings = debounce(() => api("POST", "prefs", { settings: state.settings }).catch(() => {}), 600);

function setSettings(changes, { preview = true } = {}) {
  Object.assign(state.settings, changes);
  if (state.settings.booklet) { state.settings.pages_per_side = 2; state.settings.sides = "short"; }
  renderControls();
  persistSettings();
  if (preview) refreshPreviewSoon();
}

function bindControls() {
  $$('input[name="sides"]').forEach((r) => r.addEventListener("change", () => setSettings({ sides: r.value })));
  $$('input[name="layout"]').forEach((r) =>
    r.addEventListener("change", () => {
      if (r.value === "booklet") setSettings({ booklet: true });
      else {
        const wasBooklet = state.settings.booklet;
        setSettings({ booklet: false, pages_per_side: Number(r.value), ...(wasBooklet ? { sides: "long" } : {}) });
      }
    }),
  );
  $$('input[name="color"]').forEach((r) => r.addEventListener("change", () => setSettings({ color: r.value })));
  $$('input[name="quality"]').forEach((r) => r.addEventListener("change", () => setSettings({ quality: r.value })));
  $$('input[name="duplex_method"]').forEach((r) => r.addEventListener("change", () => setSettings({ duplex_method: r.value })));

  $$("[data-setting]").forEach((input) => {
    const key = input.dataset.setting;
    const read = () => {
      if (input.type === "checkbox") return input.checked;
      if (["pages_per_side", "copies", "scale_percent", "margin_mm"].includes(key)) return Number(input.value) || state.defaults[key];
      return input.value;
    };
    const evt = input.type === "text" ? "input" : "change";
    input.addEventListener(evt, () => {
      const v = read();
      if (key === "pages_per_side") setSettings({ pages_per_side: v, booklet: false });
      else setSettings({ [key]: v });
    });
  });
  $$(".step-btn").forEach((b) =>
    b.addEventListener("click", () => {
      const n = Math.max(1, Math.min(999, (Number(state.settings.copies) || 1) + Number(b.dataset.step)));
      setSettings({ copies: n });
      announce(plural(n, "copy", "copies"));
    }),
  );
  $("#reset-settings").addEventListener("click", () => {
    const keep = { rotate_backs: state.settings.rotate_backs, reverse_backs: state.settings.reverse_backs };
    state.settings = { ...state.defaults, ...keep };
    setSettings({});
    toast("Settings reset. Your two-sided set-up was kept.");
  });
}

/* ------------------------------------------------------------------ presets */

function renderPresets() {
  const box = $("#presets");
  box.innerHTML = "";
  const all = [...BUILTIN_PRESETS, ...state.presets.map((p) => ({ ...p, custom: true }))];
  for (const p of all) {
    const chip = el("button", { type: "button", class: "chip", onclick: () => applyPreset(p) }, p.custom ? icon("star") : null, p.name);
    if (p.custom) {
      const del = el("button", { type: "button", class: "chip-del", "aria-label": `Delete quick pick ${p.name}`, onclick: (e) => { e.stopPropagation(); deletePreset(p); } }, icon("x"));
      chip.append(del);
    }
    box.append(chip);
  }
}

function applyPreset(p) {
  const keep = { rotate_backs: state.settings.rotate_backs, reverse_backs: state.settings.reverse_backs, duplex_method: state.settings.duplex_method };
  const base = p.custom ? { ...p.settings, ...keep } : p.settings;
  setSettings(base);
  toast(`Applied “${p.name}”.`, { timeout: 2500 });
}

async function savePreset() {
  const name = await promptDialog({ title: "Save as a quick pick", label: "Name", placeholder: "e.g. School notes", ok: "Save" });
  if (!name) return;
  const { presets } = await api("POST", "presets", { name, settings: state.settings });
  state.presets = presets;
  renderPresets();
  toast(`Saved “${name}”. It’s now in Quick picks.`);
}

async function deletePreset(p) {
  const { presets } = await api("DELETE", "presets/" + p.id);
  state.presets = presets;
  renderPresets();
  toast(`Deleted “${p.name}”.`);
  $("#save-preset").focus();
}

/* ------------------------------------------------------------------ preview */

const refreshPreviewSoon = debounce(() => refreshPreview(), 280);

async function refreshPreview() {
  if (!state.files.some((f) => !f.loading)) return;
  const seq = ++state.previewSeq;
  const busyTimer = setTimeout(() => ($("#preview-busy").hidden = false), 250);
  try {
    const info = await api("POST", "preview", { settings: state.settings });
    if (seq !== state.previewSeq) return;
    clearRangeError();
    state.preview = info;
    state.idx = Math.min(state.idx, info.sheets_list.length - 1);
    if (state.idx < 0) state.idx = 0;
    if (!info.two_sided) state.side = "front";
    renderSummary();
    renderThumbs();
    showSheet(false);
  } catch (e) {
    if (seq !== state.previewSeq) return;
    if (e.code === "layout") {
      showRangeError(e.message);
      $("#summary-main").textContent = "Check the page settings";
      $("#summary-sub").textContent = e.message;
    } else if (e.code !== "no_files") {
      toast(e.message, { error: true });
    }
  } finally {
    clearTimeout(busyTimer);
    if (seq === state.previewSeq) $("#preview-busy").hidden = true;
  }
}

function showRangeError(msg) {
  $("#range-error").textContent = msg;
  $("#page_range").setAttribute("aria-invalid", "true");
  $("#print-btn").disabled = true;
}
function clearRangeError() {
  $("#range-error").textContent = "";
  $("#page_range").removeAttribute("aria-invalid");
  $("#print-btn").disabled = state.uploading > 0;
}

function renderSummary() {
  const p = state.preview;
  if (!p) return;
  const s = state.settings;
  const sentence = p.sentence.charAt(0).toUpperCase() + p.sentence.slice(1);
  $("#summary-main").textContent = sentence;
  const bits = [];
  bits.push(p.selected_pages === p.source_pages ? `${plural(p.source_pages, "page")}` : `${p.selected_pages} of ${p.source_pages} pages`);
  if (p.manual_duplex) bits.push("prints in two rounds: fronts, then backs");
  if (state.printer === SAVE_AS_PDF) bits.push("saved as a PDF file");
  if (s.copies > 1 && p.sheets_per_copy) bits.push(`${plural(p.sheets_per_copy, "sheet")} per copy`);
  $("#summary-sub").textContent = bits.join(" · ");
  $("#side-toggle").hidden = !p.two_sided;
}

function flipAxis(sheet) {
  const s = state.settings;
  const land = sheet.front.landscape;
  const long = s.sides === "long";
  // long-edge flip turns around the long side of the paper
  return long !== land ? "y" : "x";
}

function sizeSheet() {
  const p = state.preview;
  if (!p || !p.sheets_list.length) return;
  const sheet = p.sheets_list[state.idx];
  const wrap = $("#sheet-wrap");
  const availW = wrap.clientWidth - 8;
  const availH = wrap.clientHeight - ($("#sheet-caption").offsetHeight + 16);
  if (availW <= 0 || availH <= 0) return;
  const ratio = sheet.front.landscape ? 1 / p.paper_ratio : p.paper_ratio; // h / w
  let w = availW;
  let h = w * ratio;
  if (h > availH) { h = availH; w = h / ratio; }
  const elx = $("#sheet");
  elx.style.width = Math.floor(w) + "px";
  elx.style.height = Math.floor(h) + "px";
  return { w, h };
}

function showSheet(animate = true) {
  const p = state.preview;
  if (!p || !p.sheets_list.length) return;
  const sheet = p.sheets_list[state.idx];
  const size = sizeSheet() || { w: 400 };
  const px = Math.min(1600, size.w * (window.devicePixelRatio || 1));
  const front = $("#img-front");
  const back = $("#img-back");
  const src = imgUrl(sheet.front.img, px);
  if (front.dataset.src !== src) { front.src = src; front.dataset.src = src; }
  front.alt = `Front of sheet ${state.idx + 1}: ${pagesText(sheet.front.pages)}`;
  const backFace = $(".face-back");
  const axis = flipAxis(sheet);
  backFace.classList.toggle("axis-x", axis === "x");
  backFace.classList.toggle("axis-y", axis === "y");
  if (sheet.back) {
    const bsrc = imgUrl(sheet.back.img, px);
    if (back.dataset.src !== bsrc) { back.src = bsrc; back.dataset.src = bsrc; }
    back.alt = `Back of sheet ${state.idx + 1}: ${pagesText(sheet.back.pages)}`;
    backFace.classList.remove("is-blank");
  } else {
    back.removeAttribute("src");
    back.dataset.src = "";
    back.alt = "";
    backFace.classList.add("is-blank");
  }
  const sh = $("#sheet");
  if (!animate) sh.style.transition = "none";
  sh.classList.remove("flipped-x", "flipped-y");
  if (state.side === "back" && p.two_sided) sh.classList.add("flipped-" + axis);
  if (!animate) { sh.offsetHeight; sh.style.transition = ""; }
  $(".face-front").setAttribute("aria-hidden", state.side === "back" ? "true" : "false");
  $(".face-back").setAttribute("aria-hidden", state.side === "back" ? "false" : "true");

  const n = p.sheets_list.length;
  const which = state.side === "back" ? sheet.back : sheet.front;
  const cap = $("#sheet-caption");
  cap.innerHTML = "";
  cap.append(
    el("b", {}, `Sheet ${state.idx + 1} of ${n}${p.truncated ? "+" : ""}`),
    p.two_sided ? ` · ${state.side === "back" ? "back" : "front"}` : "",
    ` · ${pagesText(which ? which.pages : [])}`,
  );
  $("#prev-sheet").disabled = state.idx === 0;
  $("#next-sheet").disabled = state.idx >= n - 1;
  $$('#side-toggle button').forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.side === state.side)));
  $$(".thumb").forEach((t, i) => t.setAttribute("aria-current", String(i === state.idx)));
  const cur = $$(".thumb")[state.idx];
  cur?.scrollIntoView({ block: "nearest", inline: "nearest", behavior: state.look.motion ? "auto" : "smooth" });
}

function renderThumbs() {
  const box = $("#thumbs");
  box.innerHTML = "";
  const p = state.preview;
  if (!p || p.sheets_list.length < 2) return;
  const dpr = window.devicePixelRatio || 1;
  p.sheets_list.forEach((sheet, i) => {
    const label = `Sheet ${i + 1}: front ${pagesText(sheet.front.pages)}${p.two_sided ? `, back ${sheet.back ? pagesText(sheet.back.pages) : "blank"}` : ""}`;
    const imgs = el("span", { class: "thumb-imgs", "aria-hidden": "true" },
      el("img", { src: imgUrl(sheet.front.img, 90 * dpr), alt: "", loading: "lazy" }),
      sheet.back ? el("img", { class: "back", src: imgUrl(sheet.back.img, 70 * dpr), alt: "", loading: "lazy" }) : null,
    );
    const btn = el("button", { type: "button", class: "thumb", "aria-label": label, "aria-current": String(i === state.idx),
      onclick: () => { state.idx = i; showSheet(); } }, imgs, el("span", { "aria-hidden": "true" }, String(i + 1)));
    box.append(el("div", { role: "listitem" }, btn));
  });
}

function bindPreview() {
  $("#prev-sheet").addEventListener("click", () => go(-1));
  $("#next-sheet").addEventListener("click", () => go(1));
  $$("#side-toggle button").forEach((b) => b.addEventListener("click", () => { state.side = b.dataset.side; showSheet(); }));
  $("#sheet").addEventListener("click", () => flip());
  if ("ResizeObserver" in window) new ResizeObserver(() => sizeSheet()).observe($("#sheet-wrap"));
  else window.addEventListener("resize", debounce(() => sizeSheet(), 80));
}

function go(delta) {
  const p = state.preview;
  if (!p) return;
  const n = p.sheets_list.length;
  const next = Math.max(0, Math.min(n - 1, state.idx + delta));
  if (next !== state.idx) { state.idx = next; showSheet(); }
}

function flip() {
  if (!state.preview?.two_sided) return;
  state.side = state.side === "front" ? "back" : "front";
  showSheet();
}

/* ------------------------------------------------------------------ dialogs */

function openDialog(id) {
  const d = document.getElementById(id);
  if (!d.open) d.showModal();
  return d;
}

function promptDialog({ title, label, placeholder = "", type = "text", ok = "OK" }) {
  return new Promise((resolve) => {
    const d = $("#prompt-dialog");
    $("#prompt-title").textContent = title;
    $("#prompt-label").textContent = label;
    const input = $("#prompt-input");
    input.type = type;
    input.value = "";
    input.placeholder = placeholder;
    $("#prompt-ok").textContent = ok;
    d.returnValue = "";
    d.addEventListener("close", function handler() {
      d.removeEventListener("close", handler);
      resolve(d.returnValue === "ok" ? input.value.trim() : null);
    });
    d.showModal();
    input.focus();
  });
}

function bindDialogs() {
  $$("[data-open]").forEach((b) => b.addEventListener("click", () => {
    if (b.dataset.open === "history-dialog") loadHistory();
    openDialog(b.dataset.open);
  }));
  $$("[data-close]").forEach((b) => b.addEventListener("click", () => b.closest("dialog").close()));
  $$("dialog").forEach((d) => d.addEventListener("click", (e) => {
    if (e.target === d && d.id !== "wizard") d.close(); // click on backdrop
  }));
}

/* ------------------------------------------------------------------ history */

async function loadHistory() {
  try {
    const { history } = await api("GET", "history");
    state.history = history;
  } catch { /* ignore */ }
  renderHistory();
}

function renderHistory() {
  const list = $("#history-list");
  list.innerHTML = "";
  $("#history-empty").hidden = state.history.length > 0;
  $("#history-clear").hidden = state.history.length === 0;
  for (const h of state.history) {
    const when = new Date(h.time * 1000).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
    const s = h.settings;
    const what = [
      plural(h.sheets, "sheet"),
      s.sides === "one" ? "one side" : `both sides (${s.sides} edge)`,
      s.booklet ? "booklet" : s.pages_per_side > 1 ? `${s.pages_per_side} per side` : null,
      s.color === "bw" ? "B&W" : "colour",
    ].filter(Boolean).join(" · ");
    list.append(el("li", {},
      el("div", { class: "h-main" },
        el("div", { class: "h-title" }, h.title),
        el("div", { class: "h-meta" }, `${when} · ${what}`)),
      el("button", { type: "button", class: "btn btn-secondary btn-sm", "aria-label": `Open ${h.title} again with the same settings`, onclick: () => reprint(h) }, "Use again"),
    ));
  }
}

async function reprint(h) {
  try {
    const res = await api("POST", `history/${h.id}/reprint`);
    state.files = res.files;
    if (state.printers.some((p) => p.name === res.printer)) state.printer = res.printer;
    $("#printer").value = state.printer;
    state.settings = { ...state.defaults, ...res.settings };
    $("#history-dialog").close();
    renderFiles();
    renderControls();
    refreshPreview();
    toast(`Loaded “${h.title}” with the same settings. Press Print when ready.`);
    $("#print-btn").focus();
  } catch (e) {
    toast(e.message, { error: true });
  }
}

/* ------------------------------------------------------------------ printing & the two-sided wizard */

let wiz = null; // { jobId, result, calibration }

function speak(text) {
  if (!state.look.speak || !("speechSynthesis" in window)) return;
  window.speechSynthesis.cancel();
  const u = new SpeechSynthesisUtterance(text);
  u.rate = 0.95;
  window.speechSynthesis.speak(u);
}

function wizardRender({ title, body, actions, progress }) {
  $("#wiz-title").textContent = title;
  const b = $("#wiz-body");
  b.innerHTML = "";
  b.append(...body);
  const a = $("#wiz-actions");
  a.innerHTML = "";
  a.append(...actions);
  const pr = $("#wiz-progress");
  pr.innerHTML = "";
  if (progress) for (let i = 0; i < progress[1]; i++) pr.append(el("span", { class: i < progress[0] ? "on" : "" }));
  const d = openDialog("wizard");
  const primary = a.querySelector(".btn-primary") || a.querySelector("button");
  setTimeout(() => (primary || d).focus(), 50);
  speak(`${title}. ${b.innerText}`);
}

function speakButton() {
  if (!("speechSynthesis" in window)) return null;
  return el("button", { type: "button", class: "btn btn-ghost spacer", onclick: () => {
    const prev = state.look.speak; state.look.speak = true;
    speak(`${$("#wiz-title").textContent}. ${$("#wiz-body").innerText}`);
    state.look.speak = prev;
  } }, icon("speak"), "Read aloud");
}

function closeWizard() {
  if ("speechSynthesis" in window) window.speechSynthesis.cancel();
  $("#wizard").close();
  wiz = null;
}

async function discardPending() {
  if (wiz?.jobId) api("POST", "print/discard", { job_id: wiz.jobId }).catch(() => {});
  closeWizard();
  toast("Stopped. The back sides were not printed.");
}

const flipArt = () => {
  const wrap = el("div", { class: "wiz-art", "aria-hidden": "true" });
  wrap.innerHTML = `
  <svg viewBox="0 0 420 170" role="presentation">
    <!-- step 1: printed pile, face up -->
    <g transform="translate(20,20)">
      <rect x="6" y="6" width="80" height="110" rx="3" class="p-front"/>
      <rect x="0" y="0" width="80" height="110" rx="3" class="p-printed"/>
      <path d="M14 18h52M14 28h52M14 38h40M14 52h52M14 62h46" class="lines"/>
      <text x="40" y="136" text-anchor="middle" class="lbl">Printed pile</text>
    </g>
    <!-- arrow: turn over like a book page -->
    <g transform="translate(128,40)">
      <path d="M0 40 C 20 -10, 70 -10, 90 40" class="arr"/>
      <path d="M80 34 L90 40 L94 28" class="arr"/>
      <text x="45" y="70" text-anchor="middle" class="lbl">Turn over</text>
      <text x="45" y="86" text-anchor="middle" class="lbl">like a book page</text>
    </g>
    <!-- step 2: blank side up, into rear tray -->
    <g transform="translate(240,20)">
      <rect x="0" y="0" width="80" height="110" rx="3" class="p-front"/>
      <path d="M40 -2 v-14 M32 -9 l8 -8 8 8" class="arr"/>
      <text x="40" y="60" text-anchor="middle" class="lbl">blank</text>
      <text x="40" y="76" text-anchor="middle" class="lbl">side up</text>
      <text x="40" y="136" text-anchor="middle" class="lbl">Top edge in first</text>
    </g>
    <g transform="translate(340,30)">
      <path d="M8 90 L8 30 Q8 20 18 20 L62 20 Q72 20 72 30 L72 90 Z" class="printer-body"/>
      <path d="M18 20 L26 0 L54 0 L62 20" class="printer-body"/>
      <text x="40" y="126" text-anchor="middle" class="lbl">Rear tray</text>
    </g>
  </svg>`;
  return wrap;
};

async function doPrint() {
  if ($("#print-btn").disabled) return;
  if (!state.files.some((f) => !f.loading)) { $("#file-input").click(); return; }
  const btn = $("#print-btn");
  btn.disabled = true;
  const label = $("#print-label").textContent;
  $("#print-label").textContent = state.printer === SAVE_AS_PDF ? "Saving…" : "Sending…";
  try {
    const res = await api("POST", "print", { printer: state.printer, settings: state.settings });
    await handlePrintResult(res, false);
  } catch (e) {
    toast(e.message, { error: true });
  } finally {
    btn.disabled = false;
    $("#print-label").textContent = label;
    pollStatus();
  }
}

async function handlePrintResult(res, calibration) {
  if (state.printer === SAVE_AS_PDF) {
    // No paper to turn over: save the remaining parts straight away.
    const saved = [res.saved_to];
    let r = res;
    while (!r.done) { r = await api("POST", "print/continue", { job_id: r.job_id }); saved.push(r.saved_to); }
    toast(saved.length > 1 ? `Saved ${saved.length} PDF files (fronts and backs) in your Canon Print folder.` : "Saved as a PDF file.", {
      action: { label: "Show file", run: () => api("POST", "open-folder", { path: saved[0] }) },
      timeout: 9000,
    });
    announce("PDF saved.");
    return;
  }
  if (!res.manual_duplex || res.done) {
    const sheets = plural(res.sheets, "sheet");
    showDone({ title: "Sent to the printer", lead: `${sheets} on the way.`, extra: state.settings.last_page_first ? "Pages will land in order, face up." : null });
    return;
  }
  wiz = { jobId: res.job_id, result: res, calibration };
  wizardFronts();
}

function wizardFronts() {
  const r = wiz.result;
  wizardRender({
    title: "Step 1 of 3 · Printing the fronts",
    progress: [1, 3],
    body: [
      el("div", { class: "status-line" }, el("span", { class: "spinner", "aria-hidden": "true" }), `Printing the front sides of ${plural(r.sheets, "sheet")}…`),
      el("p", { class: "lead" }, `Wait until all ${plural(r.sheets, "sheet")} have come out of the printer.`),
      el("p", { class: "muted" }, "Leave them in the output tray for now, and don’t change their order."),
    ],
    actions: [
      speakButton(),
      el("button", { type: "button", class: "btn btn-ghost", onclick: discardPending }, "Stop"),
      el("button", { type: "button", class: "btn btn-primary", onclick: wizardTurn }, "All fronts are out", icon("right")),
    ].filter(Boolean),
  });
}

function wizardTurn() {
  const r = wiz.result;
  const steps = el("ol", { class: "wiz-steps" });
  if (r.remove_top_sheet)
    steps.append(el("li", { class: "attention" }, el("span", {}, el("strong", {}, "Take the top sheet off the pile"), " and put it aside. It’s the last page and has nothing on the back.")));
  steps.append(
    el("li", {}, el("span", {}, "Pick up the printed pile. ", el("strong", {}, "Keep it in the same order."))),
    el("li", {}, el("span", {}, el("strong", {}, "Turn the whole pile over like a page in a book"), " (from left to right). The printed side is now face down, and the top of the pages still points away from you.")),
    el("li", {}, el("span", {}, "Put it in the ", el("strong", {}, "rear paper tray"), ", top edge going in first, with no other paper in the tray.")),
  );
  wizardRender({
    title: "Step 2 of 3 · Put the pile back in",
    progress: [2, 3],
    body: [flipArt(), steps],
    actions: [
      speakButton(),
      el("button", { type: "button", class: "btn btn-ghost", onclick: discardPending }, "Stop"),
      el("button", { type: "button", class: "btn btn-primary", onclick: wizardBacks }, icon("printer"), "Print the backs"),
    ].filter(Boolean),
  });
}

async function wizardBacks() {
  const btn = $("#wiz-actions .btn-primary");
  btn.disabled = true;
  btn.lastChild.textContent = "Sending…";
  try {
    const r = await api("POST", "print/continue", { job_id: wiz.jobId });
    if (wiz.calibration) return wizardCalibrationQuestions();
    showDone({
      title: "Step 3 of 3 · All done!",
      lead: `Your ${plural(wiz.result.sheets, "sheet")} are printing on the back now.`,
      extra: "When they’re out, turn the pile over like a book page. Page 1 will be on top.",
      progress: [3, 3],
      r,
    });
  } catch (e) {
    toast(e.message, { error: true });
    btn.disabled = false;
    btn.lastChild.textContent = "Print the backs";
  }
}

function showDone({ title, lead, extra, progress }) {
  const wrong = wiz && !wiz.calibration
    ? el("p", { class: "muted" }, "Backs upside down or on the wrong sheets? Run the two-sided test once: Printer → Two-sided test.")
    : null;
  wizardRender({
    title,
    progress,
    body: [
      el("div", { class: "done-badge", "aria-hidden": "true" }, icon("check")),
      el("p", { class: "lead", style: "text-align:center" }, lead),
      extra ? el("p", { style: "text-align:center" }, extra) : null,
      wrong,
    ].filter(Boolean),
    actions: [el("button", { type: "button", class: "btn btn-primary", onclick: closeWizard }, "Done")],
  });
  announce(`${title}. ${lead}`);
}

/* ------------------------------------------------------------------ two-sided test */

async function runCalibration() {
  closeAll();
  if (state.printer === SAVE_AS_PDF) { toast("Choose your printer first. The test needs real paper.", { error: true }); return; }
  try {
    const res = await api("POST", "calibrate", { printer: state.printer, settings: state.settings });
    wiz = { jobId: res.job_id, result: res, calibration: true };
    wizardRender({
      title: "Two-sided test · Step 1",
      progress: [1, 3],
      body: [
        el("p", { class: "lead" }, "This prints 2 sheets with big numbers, so you can see how your printer handles two-sided printing. You only need to do it once."),
        el("div", { class: "status-line" }, el("span", { class: "spinner", "aria-hidden": "true" }), "Printing pages 1 and 3…"),
        el("p", { class: "muted" }, "Wait until both sheets are out."),
      ],
      actions: [
        el("button", { type: "button", class: "btn btn-ghost", onclick: discardPending }, "Stop"),
        el("button", { type: "button", class: "btn btn-primary", onclick: wizardTurn }, "Both sheets are out", icon("right")),
      ],
    });
  } catch (e) {
    toast(e.message, { error: true });
  }
}

function wizardCalibrationQuestions() {
  const q = (name, text) => el("div", { class: "q", role: "radiogroup", "aria-label": text },
    el("p", {}, text),
    el("div", { class: "opts" },
      el("label", { class: "check" }, el("input", { type: "radio", name, value: "yes", checked: true }), "Yes"),
      el("label", { class: "check" }, el("input", { type: "radio", name, value: "no" }), "No")));
  wizardRender({
    title: "Two-sided test · Check the result",
    progress: [3, 3],
    body: [
      el("p", { class: "lead" }, "Pick up the sheet with page 1 and turn it over like a book page."),
      q("cal-order", "Is page 2 on the back of page 1?"),
      q("cal-rot", "Is page 2 the right way up (same top as page 1)?"),
    ],
    actions: [
      el("button", { type: "button", class: "btn btn-ghost", onclick: closeWizard }, "Skip"),
      el("button", { type: "button", class: "btn btn-primary", onclick: () => {
        const order = $('input[name="cal-order"]:checked').value === "yes";
        const rot = $('input[name="cal-rot"]:checked').value === "yes";
        const changes = {};
        if (!order) changes.reverse_backs = !state.settings.reverse_backs;
        if (!rot) changes.rotate_backs = !state.settings.rotate_backs;
        setSettings(changes, { preview: false });
        closeWizard();
        toast(order && rot ? "Your printer is set up correctly. Nothing to change." : "Fixed! Two-sided printing is now adjusted for your printer. Run the test again to double-check.", { timeout: 9000 });
      } }, "Save"),
    ],
  });
}

function closeAll() { $$("dialog[open]").forEach((d) => d.id !== "wizard" && d.close()); }

/* ------------------------------------------------------------------ printer tools */

function bindTools() {
  $("#printer").addEventListener("change", (e) => {
    state.printer = e.target.value;
    api("POST", "prefs", { printer: state.printer }).catch(() => {});
    $("#printer-detail").textContent = `Using: ${labelFor(state.printer)}`;
    updatePrintLabel();
    renderSummary();
    pollStatus();
    announce(`Printer: ${labelFor(state.printer)}`);
  });
  $("#tool-test").addEventListener("click", runCalibration);
  $("#run-test").addEventListener("click", runCalibration);
  $("#tool-cancel").addEventListener("click", async () => {
    try { const r = await api("POST", "cancel-queue", { printer: state.printer }); toast(r.message); pollStatus(); }
    catch (e) { toast(e.message, { error: true }); }
  });
  $("#tool-refresh").addEventListener("click", () => refreshPrinters().catch((e) => toast(e.message, { error: true })));
  $("#tool-folder").addEventListener("click", () => api("POST", "open-folder", {}).catch((e) => toast(e.message, { error: true })));
  const driver = async () => {
    toast("The printer’s settings window is opening. It may appear behind this browser window.", { timeout: 5000 });
    try { const r = await api("POST", "driver-dialog", { printer: state.printer }); if (r.ok) toast("Printer settings saved for this session."); }
    catch (e) { toast(e.message, { error: true }); }
  };
  $("#tool-driver").addEventListener("click", driver);
  $("#driver-dialog").addEventListener("click", driver);
  $("#tool-quit").addEventListener("click", async () => {
    await api("POST", "quit").catch(() => {});
    document.body.innerHTML = '<main style="display:grid;place-items:center;min-height:100vh;text-align:center;padding:2rem"><div><h1 style="font-size:1.6rem;margin-bottom:.5rem">Canon Print has stopped.</h1><p class="muted">You can close this tab. Start Canon Print again whenever you need to print.</p></div></main>';
  });
  $("#history-clear").addEventListener("click", async () => {
    const { history } = await api("POST", "history/clear");
    state.history = history;
    renderHistory();
    toast("History cleared.");
  });
}

/* ------------------------------------------------------------------ keyboard */

function bindKeys() {
  document.addEventListener("keydown", (e) => {
    const mod = e.ctrlKey || e.metaKey;
    const typing = /^(INPUT|SELECT|TEXTAREA)$/.test(document.activeElement?.tagName) &&
      !["radio", "checkbox"].includes(document.activeElement.type);
    const dialogOpen = !!document.querySelector("dialog[open]");
    if (mod && e.key.toLowerCase() === "o") { e.preventDefault(); $("#file-input").click(); return; }
    if (mod && e.key.toLowerCase() === "p") { e.preventDefault(); if (!dialogOpen) doPrint(); return; }
    if (typing || dialogOpen || mod || e.altKey) return;
    const inSettings = document.activeElement?.closest?.(".settings");
    if (e.key === "?") { e.preventDefault(); openDialog("help-dialog"); }
    else if (inSettings) return;
    else if (e.key === "ArrowLeft") { e.preventDefault(); go(-1); }
    else if (e.key === "ArrowRight") { e.preventDefault(); go(1); }
    else if (e.key.toLowerCase() === "f") { e.preventDefault(); flip(); }
  });
}

/* ------------------------------------------------------------------ start */

async function init() {
  bindLook();
  bindFiles();
  bindControls();
  bindPreview();
  bindDialogs();
  bindTools();
  bindKeys();
  $("#print-btn").addEventListener("click", doPrint);
  $("#save-preset").addEventListener("click", savePreset);

  try {
    const s = await api("GET", "state");
    state.settings = s.settings;
    state.defaults = s.defaults;
    state.printers = s.printers;
    state.printer = s.printer;
    state.presets = s.presets;
    state.history = s.history;
    state.files = s.files;
    state.platform = s.platform;
    state.look = { ...state.look, ...(s.look || {}) };
    const paper = $("#paper");
    for (const p of s.papers) paper.append(el("option", { value: p.id }, p.label));
    $("#folder-path").textContent = s.output_folder;
    $("#tool-driver").hidden = !s.driver_dialog;
    $("#driver-dialog").hidden = !s.driver_dialog;
    applyLook();
    renderPrinters();
    renderControls();
    renderPresets();
    renderFiles();
    if (state.files.length) refreshPreview();
  } catch (e) {
    toast(e.message, { error: true, timeout: 0 });
  }
  setInterval(() => { if (!document.hidden) pollStatus(); }, 5000);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) pollStatus(); });
}

document.addEventListener("DOMContentLoaded", init);
