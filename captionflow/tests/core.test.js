const test = require('node:test');
const assert = require('node:assert');
const U = require('../js/core/util.js');
const C = require('../js/core/captions.js');
const S = require('../js/core/silence.js');

function words(str, step = 0.4, gapAt = {}) {
  let t = 0;
  return str.split(' ').map((w, i) => {
    t += gapAt[i] || 0;
    const o = { text: w, start: t, end: t + step * 0.9 };
    t += step;
    return o;
  });
}

test('buildBlocks respeita maxChars e maxLines', () => {
  const ws = words('Hoje vamos falar sobre como editar videos de forma muito mais rapida e inteligente no Premiere Pro');
  const blocks = C.buildBlocks(ws, { maxChars: 20, maxLines: 2 });
  assert.ok(blocks.length > 1);
  blocks.forEach((b) => {
    const lines = C.linesText(b, { maxChars: 20, maxLines: 2 });
    assert.ok(lines.length <= 2);
    lines.forEach((l) => assert.ok(l.length <= 20, l));
  });
  assert.strictEqual(blocks.reduce((n, b) => n + b.words.length, 0), ws.length);
});

test('quebra em duas linhas balanceadas', () => {
  const b = { words: C.normalizeWords(words('uma frase um pouco longa para testar')) };
  const lines = C.linesText(b, { maxChars: 20, maxLines: 2 });
  assert.strictEqual(lines.length, 2);
  assert.ok(Math.abs(lines[0].length - lines[1].length) <= 8);
});

test('pausa longa fecha o bloco', () => {
  const blocks = C.buildBlocks(words('um dois tres quatro', 0.3, { 2: 1.5 }), { maxChars: 40 });
  assert.strictEqual(blocks.length, 2);
});

test('palavras por segundo estende a exibicao quando ha espaco', () => {
  const blocks = C.buildBlocks(words('a b c d e f', 0.1, { }), { maxWps: 3, minDuration: 0.1, maxChars: 40 });
  assert.ok(blocks[0].showEnd - blocks[0].start >= 6 / 3 - 1e-6);
  assert.strictEqual(blocks[0].warn, false);
});

test('atalhos movem palavras entre blocos', () => {
  const blocks = C.buildBlocks(words('um dois tres quatro cinco seis', 0.3, { 3: 1.5 }), { maxChars: 40 });
  assert.strictEqual(blocks.length, 2);
  C.sendLastWordForward(blocks, 0);
  assert.strictEqual(blocks[0].words.length, 2);
  assert.strictEqual(blocks[1].words[0].text, 'tres');
  C.sendFirstWordBack(blocks, 1);
  assert.strictEqual(blocks[0].words.length, 3);
  C.pullFirstWordFromNext(blocks, 0);
  assert.strictEqual(blocks[0].words.length, 4);
  C.pullLastWordFromPrev(blocks, 1);
  assert.strictEqual(blocks[1].words[0].text, 'quatro');
  C.sendLastWordForward(blocks, 1);
  C.sendLastWordForward(blocks, 1);
  C.sendLastWordForward(blocks, 1);
  assert.ok(blocks.every((b) => b.words.length > 0));
});

test('split e merge preservam palavras', () => {
  const blocks = C.buildBlocks(words('um dois tres quatro'), { maxChars: 40 });
  C.splitBlock(blocks, 0, 2);
  assert.strictEqual(blocks.length, 2);
  C.mergeWithNext(blocks, 0);
  assert.strictEqual(blocks[0].words.map((w) => w.text).join(' '), 'um dois tres quatro');
});

test('setBlockText mantem tempos quando a contagem e igual e redistribui quando muda', () => {
  const b = { words: C.normalizeWords(words('ola mundo bonito')) };
  const t1 = b.words[1].start;
  C.setBlockText(b, 'oi mundo lindo');
  assert.strictEqual(b.words[1].start, t1);
  C.setBlockText(b, 'oi mundo muito lindo');
  assert.strictEqual(b.words.length, 4);
  assert.ok(b.words[3].end > b.words[0].start);
});

test('SRT bem formatado', () => {
  const blocks = C.buildBlocks(words('ola mundo'), { maxChars: 40 });
  const srt = C.toSRT(blocks);
  assert.match(srt, /^1\n00:00:00,000 --> 00:00:\d\d,\d{3}\nola mundo\n/);
});

test('deteccao de silencio, padding e minimo', () => {
  const sr = 8000, sec = 6, x = new Float32Array(sr * sec);
  for (let i = 0; i < x.length; i++) {
    const t = i / sr;
    const speech = (t < 1) || (t >= 3 && t < 4) || (t >= 5.9);
    x[i] = speech ? 0.3 * Math.sin(2 * Math.PI * 220 * t) : 0.0005;
  }
  const { db, frameSec } = S.rmsDb(x, sr, 10);
  const r = S.detect(db, frameSec, { thresholdDb: -40, minSilence: 0.4, padding: 0.1 });
  assert.strictEqual(r.silences.length, 2);
  assert.ok(Math.abs(r.silences[0].start - 1.1) < 0.03);
  assert.ok(Math.abs(r.silences[0].end - 2.9) < 0.03);
  assert.ok(r.keep.length >= 2);
  const rLong = S.detect(db, frameSec, { minSilence: 3 });
  assert.strictEqual(rLong.silences.length, 0);
});

test('remapToCuts reposiciona palavras e descarta as removidas', () => {
  const blocks = C.buildBlocks(words('um dois tres quatro', 1), { maxChars: 40 });
  const out = C.remapToCuts(blocks, [{ start: 0, end: 1.9 }, { start: 3, end: 4 }]);
  const ws = out[0].words;
  assert.strictEqual(ws.map((w) => w.text).join(' '), 'um dois quatro');
  assert.ok(Math.abs(ws[2].start + ws[2].end - 2 * 2.45) < 0.1 || ws[2].start > 1.9);
});

test('J-cut e L-cut respeitam folgas e alternam trilhas', () => {
  const keep = [{ start: 0, end: 2 }, { start: 3, end: 5 }, { start: 5.2, end: 8 }];
  const j = S.jlSegments(keep, { mode: 'j', lead: 0.5, total: 9 });
  assert.strictEqual(j[0].aTlStart, 0);
  assert.ok(Math.abs(j[1].aTlStart - (2 - 0.5)) < 1e-9);
  assert.ok(Math.abs(j[1].aSrcStart - 2.5) < 1e-9);
  assert.ok(j[2].aTlStart > j[2].tlStart - 0.21);
  assert.notStrictEqual(j[0].aTrack, j[1].aTrack);
  const l = S.jlSegments(keep, { mode: 'l', tail: 0.5, total: 9 });
  assert.ok(Math.abs(l[0].aTlEnd - 2.5) < 1e-9);
  assert.strictEqual(l[2].aTlEnd, l[2].tlEnd);
  const n = S.jlSegments(keep, { mode: 'none' });
  assert.ok(n.every((s) => s.aTrack === 0 && s.aTlStart === s.tlStart));
});

test('protectWords une palavras aos trechos mantidos', () => {
  const keep = [{ start: 0, end: 1 }, { start: 2, end: 3 }];
  const out = S.protectWords(keep, [{ start: 0.9, end: 1.4 }], 0.05, 3);
  assert.ok(out[0].end >= 1.45 - 1e-9);
});

test('animacoes: fade/pop/slide e keyframes coerentes', () => {
  const R = require('../js/core/render.js');
  const a = { entry: 'fade', exit: 'fade', inDur: 0.2, outDur: 0.2 };
  assert.strictEqual(R.draw && 1, 1);
  const f = (t) => require('../js/core/render.js') && globalThis.CF.anim.blockTransform(a, t, 2);
  assert.ok(f(0).alpha < 0.01);
  assert.ok(Math.abs(f(1).alpha - 1) < 1e-9);
  assert.ok(f(2).alpha < 0.01);
  const pop = globalThis.CF.anim.blockTransform({ entry: 'pop', inDur: 0.2 }, 0.1, 5);
  assert.ok(pop.scale > 0.6 && pop.alpha > 0);
  const keys = R.keyframes({ size: 60, anim: { entry: 'slideUp', exit: 'fade', inDur: 0.2, outDur: 0.2 } }, 1920, 1080, 2, true, true);
  assert.ok(keys.some((k) => k.prop === 'Opacity' && k.t === 0 && k.v === 0));
  assert.ok(keys.some((k) => k.prop === 'Opacity' && k.t === 2 && k.v === 0));
  const st = R.states({ start: 0, end: 1, showEnd: 1, words: [{ text: 'a', start: 0, end: 0.4 }, { text: 'b', start: 0.5, end: 1 }] }, { highlight: { mode: 'color' } });
  assert.strictEqual(st.length, 2);
});

test('presets: built-in e import/export', () => {
  const P = require('../js/core/presets.js');
  assert.ok(P.all().length >= 6);
  assert.strictEqual(P.get('karaoke').highlight.mode, 'fill');
  assert.strictEqual(P.get('inexistente').id, 'clean');
});

test('parsers de transcricao (OpenAI e whisper.cpp)', () => {
  globalThis.CF = globalThis.CF || {};
  const T = require('../js/node/transcribe.js');
  const o = T.fromOpenAI({ words: [{ word: 'ola', start: 0, end: 0.3 }, { word: 'mundo', start: 0.4, end: 0.8 }], language: 'portuguese' }, 10);
  assert.strictEqual(o.words[1].start, 10.4);
  const w = T.fromWhisperCpp({ transcription: [
    { text: ' Ol', offsets: { from: 0, to: 200 } }, { text: 'a,', offsets: { from: 200, to: 350 } },
    { text: ' [MUSIC]', offsets: { from: 350, to: 400 } }, { text: ' mundo', offsets: { from: 400, to: 800 } }
  ] }, 0);
  assert.deepStrictEqual(w.words.map((x) => x.text), ['Ola,', 'mundo']);
  assert.strictEqual(w.words[0].end, 0.35);
});
