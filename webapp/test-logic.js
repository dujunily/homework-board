#!/usr/bin/env node
/**
 * 网页端逻辑冒烟测试（不依赖浏览器）。
 * 把 index.html 里的 <script> 拿出来，喂一套假的 DOM + 假目录句柄（落在真实磁盘），
 * 然后真的跑 save()，检查写出来的 day/manifest 是否符合数据契约、会不会覆盖别人的条目。
 */
const fs = require('fs');
const os = require('os');
const path = require('path');

// 用系统临时目录，Windows / macOS / Linux 都能落盘（写死 /tmp 在 Windows 上会落到当前盘根目录）
const TMP = path.join(os.tmpdir(), 'homework-board-webtest');
const ROOT = path.join(TMP, '作业板');
fs.rmSync(TMP, { recursive: true, force: true });
fs.mkdirSync(ROOT, { recursive: true });

/* ---------------- 假目录句柄，落在真实磁盘 ---------------- */
class FakeFileHandle {
  constructor(p) { this.p = p; }
  async getFile() {
    const buf = fs.readFileSync(this.p);
    return { text: async () => buf.toString('utf8'), arrayBuffer: async () => buf.buffer };
  }
  async createWritable() {
    const p = this.p;
    const chunks = [];
    return {
      write: async (d) => { chunks.push(Buffer.isBuffer(d) ? d : Buffer.from(String(d))); },
      close: async () => { fs.mkdirSync(path.dirname(p), { recursive: true }); fs.writeFileSync(p, Buffer.concat(chunks)); },
    };
  }
}
class FakeDirHandle {
  constructor(p) { this.p = p; this.name = path.basename(p); }
  async getDirectoryHandle(name, opts = {}) {
    const dir = path.join(this.p, name);
    if (!fs.existsSync(dir)) {
      if (!opts.create) throw new Error('NotFound');
      fs.mkdirSync(dir, { recursive: true });
    }
    return new FakeDirHandle(dir);
  }
  async getFileHandle(name, opts = {}) {
    const f = path.join(this.p, name);
    if (!fs.existsSync(f) && !opts.create) throw new Error('NotFound');
    return new FakeFileHandle(f);
  }
  async queryPermission() { return 'granted'; }
  async requestPermission() { return 'granted'; }
}

/* ---------------- 假 DOM ---------------- */
function el() {
  const node = {
    textContent: '', value: '', hidden: false, className: '', disabled: false,
    style: {}, files: [],
    classList: { add() {}, remove() {}, toggle() {}, contains: () => false },
    addEventListener() {}, removeEventListener() {},
    appendChild() {}, append() {}, prepend() {}, replaceChildren() {}, remove() {},
    setAttribute() {}, removeAttribute() {}, getAttribute: () => null,
    insertBefore() {}, cloneNode: () => el(), focus() {}, blur() {}, click() {},
    querySelector: () => el(), querySelectorAll: () => [],
    onclick: null, onchange: null, oninput: null,
  };
  return node;
}
const els = {};
const E = (id) => (els[id] ||= el());
const document = {
  getElementById: (id) => (els[id] ||= el()),
  createElement: (tag) => {
    const n = el();
    n.tag = tag;
    if (tag === 'canvas') {
      n.getContext = () => ({ drawImage() {}, fillRect() {}, fillText() {} });
      n.toBlob = (cb) => cb(Buffer.from('fakejpeg'));
    }
    return n;
  },
  addEventListener() {},
  body: el(),
};
const localStorage = {
  _d: {},
  getItem(k) { return this._d[k] ?? null; },
  setItem(k, v) { this._d[k] = String(v); },
  removeItem(k) { delete this._d[k]; },
};
const window = { showDirectoryPicker: async () => new FakeDirHandle(ROOT) };
const indexedDB = { open: () => ({}) };

const html = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
const script = html.match(/<script>([\s\S]*)<\/script>/)[1];

const factory = new Function(
  'document', 'window', 'indexedDB', 'localStorage', 'createImageBitmap', 'confirm', 'alert', 'navigator',
  script + '\n; return { state, save, loadDay, updateManifest, resetForm, renderList, today, nowISO, switchDate, defaultValidUntil, nextSunday, addDays, setValidUntil, renderUntil };',
);

const api = factory(
  document, window, indexedDB, localStorage,
  async () => ({ width: 1600, height: 1200 }),
  () => true,
  () => {},
  { clipboard: {} },
);

/* ---------------- 场景 ---------------- */
function readJson(rel) {
  return JSON.parse(fs.readFileSync(path.join(ROOT, rel), 'utf8'));
}
function assert(cond, msg) {
  assert.total++;
  if (!cond) { console.error('❌ ' + msg); process.exitCode = 1; } else { console.log('✅ ' + msg); }
}
assert.total = 0;

(async () => {
  const date = api.today();
  api.state.dirHandle = new FakeDirHandle(ROOT);

  // 另一台设备（手机）先写了一条，网页端必须保留它
  fs.mkdirSync(path.join(ROOT, 'day'), { recursive: true });
  fs.writeFileSync(path.join(ROOT, 'day', `${date}.json`), JSON.stringify({
    schemaVersion: 1, date, title: '今天的作业', updatedBy: '妈妈', updatedAt: api.nowISO(),
    items: [{ id: 'phone-1', subject: '语文', title: '手机写的', detail: '', order: 1, images: [] }],
    notes: '',
  }, null, 2));

  // 网页端再加两条
  for (const [i, title] of [['1', '网页第一条'], ['2', '网页第二条']]) {
    api.resetForm();
    api.state.subject = i === '1' ? '数学' : '英语';
    E('title').value = title;
    E('detail').value = '';
    E('notes').value = '明天带跳绳';
    await api.save();
  }

  const day = readJson(`day/${date}.json`);
  assert(day.items.length === 3, `应共 3 条（手机 1 + 网页 2），实际 ${day.items.length}`);
  assert(day.items.some((x) => x.id === 'phone-1'), '手机端那条没被覆盖');
  assert(day.items.map((x) => x.order).join(',') === '1,2,3', `order 连续递增，实际 ${day.items.map((x) => x.order)}`);
  assert(new Set(day.items.map((x) => x.id)).size === 3, 'id 不重复');
  assert(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$/.test(day.updatedAt), `updatedAt 是带时区的 ISO，实际 ${day.updatedAt}`);
  assert(day.notes === '明天带跳绳', 'notes 写进 day');

  const m = readJson('manifest.json');
  const entry = m.days.find((d) => d.date === date);
  assert(!!entry, 'manifest 里有今天');
  assert(entry.itemCount === 3, `manifest itemCount=3，实际 ${entry.itemCount}`);
  assert(entry.fileEtag === '', 'fileEtag 留空串（电视端降级为每次下载）');
  assert(entry.file === `day/${date}.json`, 'manifest file 路径正确');
  assert(m.days.length === 1 && m.days[0].date === date, 'manifest 只保留本次写入的日期（首次）');

  // 编辑已有条目：条数不增加
  api.resetForm();
  api.state.editingId = 'phone-1';
  E('title').value = '手机写的（网页改过）';
  await api.save();
  const day2 = readJson(`day/${date}.json`);
  assert(day2.items.length === 3, `编辑后仍是 3 条，实际 ${day2.items.length}`);
  assert(day2.items.find((x) => x.id === 'phone-1').title === '手机写的（网页改过）', '编辑生效且 id 不变');

  // 带图一条：图片应落到 img/<date>/，day 里存相对路径
  api.resetForm();
  api.state.subject = '其他';
  E('title').value = '带图那条';
  api.state.pending = [{ file: { name: 'shot.png' }, url: 'blob:fake' }];
  await api.save();
  const day3 = readJson(`day/${date}.json`);
  const withImg = day3.items.find((x) => x.title === '带图那条');
  assert(!!withImg && withImg.images.length === 1, '带图条目写入了 1 张图');
  const imgRel = withImg.images[0];
  assert(/^img\/\d{4}-\d{2}-\d{2}\/[0-9a-f]{8}\.jpg$/.test(imgRel), `图片路径合规，实际 ${imgRel}`);
  assert(fs.existsSync(path.join(ROOT, imgRel)), '图片文件真的落盘了');
  assert(!imgRel.includes('base64'), '没有把图片塞进 json');

  // 预排明天：只动明天的文件，不碰今天
  const todayCount = readJson(`day/${date}.json`).items.length;
  const [yy, mm, dd] = date.split('-').map(Number);
  const tmr = new Date(yy, mm - 1, dd + 1);
  const tmrStr = `${tmr.getFullYear()}-${String(tmr.getMonth() + 1).padStart(2, '0')}-${String(tmr.getDate()).padStart(2, '0')}`;
  await api.switchDate(tmrStr);
  assert(api.state.date === tmrStr, `切到明天成功：${tmrStr}`);
  assert(api.state.items.length === 0, '明天还是空的（没把今天的内容带过去）');
  api.resetForm();
  E('title').value = '明天要预习';
  await api.save();
  assert(readJson(`day/${tmrStr}.json`).items.length === 1, '明天的文件写入 1 条');
  assert(readJson(`day/${date}.json`).items.length === todayCount, '今天的文件没被动过');
  const m2 = readJson('manifest.json');
  assert(m2.days.length === 2, `manifest 现在有两天，实际 ${m2.days.length}`);
  assert(m2.days[0].date === tmrStr, 'manifest 按日期倒序（明天在前）');

  /* ---------- 有效期（电视端显示到哪天） ---------- */
  // 规则必须和手机端 DayRule 一致：周五、周六管到周日，其余管当天
  assert(api.defaultValidUntil('2026-11-06') === '2026-11-08', `周五默认管到周日，实际 ${api.defaultValidUntil('2026-11-06')}`);
  assert(api.defaultValidUntil('2026-11-07') === '2026-11-08', `周六默认管到周日，实际 ${api.defaultValidUntil('2026-11-07')}`);
  assert(api.defaultValidUntil('2026-11-04') === '2026-11-04', `周三默认只管当天，实际 ${api.defaultValidUntil('2026-11-04')}`);
  assert(api.nextSunday('2026-11-04') === '2026-11-08', `最近周日算对，实际 ${api.nextSunday('2026-11-04')}`);

  // 手动延长：写进 day 和 manifest 两处
  await api.switchDate(date);
  await api.setValidUntil('2026-11-09');
  assert(readJson(`day/${date}.json`).validUntil === '2026-11-09', '有效期写进 day');
  const m3 = readJson('manifest.json');
  assert(m3.days.find((d) => d.date === date).validUntil === '2026-11-09', '有效期写进 manifest（电视端靠它判断该显示哪天）');

  // 选回默认值时应写空串，保持契约干净
  await api.setValidUntil(api.defaultValidUntil(date));
  assert(readJson(`day/${date}.json`).validUntil === '', '选回默认值写空串，不污染契约');

  console.log('\n真实磁盘上的产物：');
  console.log(fs.readFileSync(path.join(ROOT, 'manifest.json'), 'utf8'));
  console.log(process.exitCode ? '\n❌ 有断言失败' : `\n✅ 全部 ${assert.total} 条断言通过`);
})();
