// CaptionFlow host script (ExtendScript / ES3). Todas as funcoes publicas recebem e devolvem strings JSON.
var CF_TICKS = 254016000000;

function cfEsc(s) {
  return String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n').replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
}
function cfJSON(v) {
  if (v === null || v === undefined) return 'null';
  var t = typeof v;
  if (t === 'number') return isFinite(v) ? String(v) : 'null';
  if (t === 'boolean') return v ? 'true' : 'false';
  if (t === 'string') return '"' + cfEsc(v) + '"';
  if (v instanceof Array) { var a = []; for (var i = 0; i < v.length; i++) a.push(cfJSON(v[i])); return '[' + a.join(',') + ']'; }
  var o = []; for (var k in v) if (v.hasOwnProperty(k)) o.push('"' + cfEsc(k) + '":' + cfJSON(v[k]));
  return '{' + o.join(',') + '}';
}
function cfParse(s) { return eval('(' + s + ')'); }
function cfFail(e) { return cfJSON({ ok: false, error: String(e && e.message ? e.message : e) }); }
function cfTime(sec) { var t = new Time(); t.seconds = sec; return t; }

function cf_ping() { return cfJSON({ ok: true, version: app.version }); }

function cf_getContext() {
  try {
    var seq = app.project.activeSequence;
    if (!seq) return cfJSON({ ok: false, error: 'Abra uma sequencia no Premiere.' });
    var info = { ok: true, name: seq.name, width: seq.frameSizeHorizontal, height: seq.frameSizeVertical,
      fps: CF_TICKS / Number(seq.timebase), playhead: seq.getPlayerPosition().seconds,
      videoTracks: seq.videoTracks.numTracks, audioTracks: seq.audioTracks.numTracks, clip: null };
    var picked = null, sel = seq.getSelection(), i;
    for (i = 0; i < sel.length; i++) { if (sel[i].projectItem) { picked = sel[i]; break; } }
    if (!picked) {
      for (i = 0; i < seq.videoTracks.numTracks && !picked; i++) {
        var tr = seq.videoTracks[i];
        for (var c = 0; c < tr.clips.numItems; c++) {
          var ci = tr.clips[c];
          if (ci.start.seconds <= info.playhead && ci.end.seconds >= info.playhead && ci.projectItem) { picked = ci; break; }
        }
      }
    }
    if (picked) {
      info.clip = { name: picked.name, mediaPath: picked.projectItem.getMediaPath(), start: picked.start.seconds, end: picked.end.seconds,
        inPoint: picked.inPoint.seconds, outPoint: picked.outPoint.seconds, duration: picked.end.seconds - picked.start.seconds };
    }
    return cfJSON(info);
  } catch (e) { return cfFail(e); }
}

function cf_getPlayhead() {
  try { return cfJSON({ ok: true, t: app.project.activeSequence.getPlayerPosition().seconds }); } catch (e) { return cfFail(e); }
}

function cf_exportFrame(pngPath, sec) {
  try {
    var seq = app.project.activeSequence;
    var ok = false;
    try { seq.exportFramePNG(cfTime(sec), pngPath); ok = true; } catch (e1) {}
    if (!ok) { seq.exportFramePNG(String(Math.round(sec * CF_TICKS)), pngPath); }
    return cfJSON({ ok: true });
  } catch (e) { return cfFail(e); }
}

function cfFindItemByPath(node, path) {
  for (var i = 0; i < node.children.numItems; i++) {
    var ch = node.children[i];
    if (ch.type === ProjectItemType.BIN) { var r = cfFindItemByPath(ch, path); if (r) return r; }
    else { try { if (ch.getMediaPath() === path) return ch; } catch (e) {} }
  }
  return null;
}
function cfFindItemByName(node, name) {
  for (var i = 0; i < node.children.numItems; i++) {
    var ch = node.children[i];
    if (ch.type === ProjectItemType.BIN) { var r = cfFindItemByName(ch, name); if (r) return r; }
    else if (ch.name === name) return ch;
  }
  return null;
}
function cfSetTarget(track, on) { try { track.setTargeted(on, true); return true; } catch (e) { return false; } }
function cfPlace(track, item, sec) {
  try { track.overwriteClip(item, sec); } catch (e) { track.overwriteClip(item, cfTime(sec)); }
}
function cfSetInOut(item, a, b, media) {
  try { item.setInPoint(a, media); item.setOutPoint(b, media); }
  catch (e) { item.setInPoint(String(Math.round(a * CF_TICKS)), media); item.setOutPoint(String(Math.round(b * CF_TICKS)), media); }
}

// o = {mediaPath, base, name, segs:[{srcStart,srcEnd,tlStart,tlEnd,aSrcStart,aSrcEnd,aTlStart,aTrack}], jl:boolean}
function cf_roughCut(json) {
  try {
    var o = cfParse(json), warnings = [], i;
    var item = cfFindItemByPath(app.project.rootItem, o.mediaPath);
    if (!item) return cfJSON({ ok: false, error: 'Midia nao encontrada no projeto: ' + o.mediaPath });
    var oldIn = null, oldOut = null;
    try { oldIn = item.getInPoint().seconds; oldOut = item.getOutPoint().seconds; } catch (e0) {}

    app.project.createNewSequenceFromClips(o.name, [item], app.project.getInsertionBin());
    var seq = app.project.activeSequence;
    if (!seq || seq.name.indexOf(o.name) !== 0) return cfJSON({ ok: false, error: 'Nao foi possivel criar a nova sequencia.' });
    var t, c;
    for (t = 0; t < seq.videoTracks.numTracks; t++) for (c = seq.videoTracks[t].clips.numItems - 1; c >= 0; c--) seq.videoTracks[t].clips[c].remove(0, 0);
    for (t = 0; t < seq.audioTracks.numTracks; t++) for (c = seq.audioTracks[t].clips.numItems - 1; c >= 0; c--) seq.audioTracks[t].clips[c].remove(0, 0);

    var nA = seq.audioTracks.numTracks, usedLinked = 0, usedSplit = 0;
    for (i = 0; i < o.segs.length; i++) {
      var s = o.segs[i], base = o.base;
      if (!o.jl) {
        cfSetInOut(item, base + s.srcStart, base + s.srcEnd, 4);
        cfPlace(seq.videoTracks[0], item, s.tlStart); usedLinked++;
        continue;
      }
      var splitOk = true, a;
      for (a = 0; a < nA; a++) cfSetTarget(seq.audioTracks[a], false);
      cfSetTarget(seq.videoTracks[0], true);
      try {
        cfSetInOut(item, base + s.srcStart, base + s.srcEnd, 1);
        cfPlace(seq.videoTracks[0], item, s.tlStart);
        cfSetTarget(seq.videoTracks[0], false);
        var at = (s.aTrack < nA) ? s.aTrack : 0;
        if (s.aTrack >= nA && warnings.length < 3) warnings.push('Poucas trilhas de audio: crie A2 para J/L cut sem sobreposicao.');
        cfSetTarget(seq.audioTracks[at], true);
        cfSetInOut(item, base + s.aSrcStart, base + s.aSrcEnd, 2);
        cfPlace(seq.audioTracks[at], item, s.aTlStart);
        cfSetTarget(seq.audioTracks[at], false);
        usedSplit++;
      } catch (e1) {
        splitOk = false;
        warnings.push('Corte ' + (i + 1) + ': J/L cut indisponivel nesta versao (' + e1.message + '). Usando corte seco.');
        cfSetInOut(item, base + s.srcStart, base + s.srcEnd, 4);
        cfPlace(seq.videoTracks[0], item, s.tlStart);
      }
    }
    for (t = 0; t < seq.videoTracks.numTracks; t++) cfSetTarget(seq.videoTracks[t], true);
    for (t = 0; t < seq.audioTracks.numTracks; t++) cfSetTarget(seq.audioTracks[t], true);
    try { if (oldIn !== null) cfSetInOut(item, oldIn, oldOut, 4); } catch (e2) {}
    return cfJSON({ ok: true, sequence: seq.name, cuts: o.segs.length, linked: usedLinked, split: usedSplit, warnings: warnings });
  } catch (e) { return cfFail(e); }
}

// ---- propriedades / keyframes ----
var CF_PROPS = {
  Opacity: { comp: 'AE.ADBE Opacity', names: ['Opacity', 'Opacidade', 'Opacidad', 'Opazit\u00e4t'] },
  Scale: { comp: 'AE.ADBE Motion', names: ['Scale', 'Escala', '\u00c9chelle'] },
  Position: { comp: 'AE.ADBE Motion', names: ['Position', 'Posi\u00e7\u00e3o', 'Posici\u00f3n', 'Posicao'] }
};
function cfFindProp(ti, key) {
  var spec = CF_PROPS[key];
  for (var i = 0; i < ti.components.numItems; i++) {
    var comp = ti.components[i], okComp = false;
    try { okComp = comp.matchName === spec.comp; } catch (e) {}
    if (!okComp && key === 'Opacity') okComp = /opac/i.test(comp.displayName);
    if (!okComp && key !== 'Opacity') okComp = /motion|movimento|movimiento/i.test(comp.displayName);
    if (!okComp) continue;
    for (var p = 0; p < comp.properties.numItems; p++) {
      var pr = comp.properties[p];
      for (var n = 0; n < spec.names.length; n++) if (pr.displayName === spec.names[n]) return pr;
    }
  }
  return null;
}
function cfApplyKeys(ti, keys) {
  var base = ti.inPoint.seconds, done = {}, j;
  for (j = 0; j < keys.length; j++) {
    var k = keys[j], pr = cfFindProp(ti, k.prop);
    if (!pr) continue;
    if (!done[k.prop]) { try { pr.setTimeVarying(true); } catch (e) {} done[k.prop] = 1; }
    var tm = cfTime(base + k.t);
    pr.addKey(tm);
    pr.setValueAtKey(tm, k.v, true);
  }
}
function cfFindPlaced(track, sec) {
  for (var i = track.clips.numItems - 1; i >= 0; i--) if (Math.abs(track.clips[i].start.seconds - sec) < 0.05) return track.clips[i];
  return null;
}

// o = {track, base, bin, items:[{png, start, end, keys:[...]}]}
function cf_insertRaster(json) {
  try {
    var o = cfParse(json), seq = app.project.activeSequence, i;
    if (!seq) return cfJSON({ ok: false, error: 'Sem sequencia ativa.' });
    var bin = null;
    for (i = 0; i < app.project.rootItem.children.numItems; i++) {
      var ch = app.project.rootItem.children[i];
      if (ch.type === ProjectItemType.BIN && ch.name === 'CaptionFlow') bin = ch;
    }
    if (!bin) bin = app.project.rootItem.createBin('CaptionFlow');
    var files = [], seen = {};
    for (i = 0; i < o.items.length; i++) if (!seen[o.items[i].png]) { seen[o.items[i].png] = 1; files.push(o.items[i].png); }
    app.project.importFiles(files, true, bin, false);
    while (seq.videoTracks.numTracks <= o.track) { try { app.enableQE(); qe.project.getActiveSequence().addTracks(1, seq.videoTracks.numTracks, 0); } catch (eq) { break; } seq = app.project.activeSequence; }
    var track = seq.videoTracks[Math.min(o.track, seq.videoTracks.numTracks - 1)], placed = 0, skipped = 0;
    for (i = 0; i < o.items.length; i++) {
      var it = o.items[i], name = it.png.replace(/^.*[\\\/]/, ''), pi = cfFindItemByName(bin, name);
      if (!pi) { skipped++; continue; }
      var st = o.base + it.start, en = o.base + it.end;
      cfPlace(track, pi, st);
      var ti = cfFindPlaced(track, st);
      if (!ti) { skipped++; continue; }
      ti.end = cfTime(en);
      if (it.keys && it.keys.length) { try { cfApplyKeys(ti, it.keys); } catch (ek) {} }
      placed++;
    }
    return cfJSON({ ok: true, placed: placed, skipped: skipped });
  } catch (e) { return cfFail(e); }
}

// o = {mogrt, track, base, textParam, items:[{text, start, end}]}
function cf_insertMogrt(json) {
  try {
    var o = cfParse(json), seq = app.project.activeSequence, placed = 0, warn = [], i;
    for (i = 0; i < o.items.length; i++) {
      var it = o.items[i], st = o.base + it.start;
      var ti = seq.importMGT(o.mogrt, String(Math.round(st * CF_TICKS)), o.track, 0);
      if (!ti) { warn.push('Falha no bloco ' + (i + 1)); continue; }
      ti.end = cfTime(o.base + it.end);
      var comp = ti.getMGTComponent(), set = false;
      for (var p = 0; p < comp.properties.numItems && !set; p++) {
        var pr = comp.properties[p], nm = pr.displayName;
        if (o.textParam ? nm === o.textParam : /text|texto|source/i.test(nm)) {
          var cur = pr.getValue(), val;
          if (typeof cur === 'string' && cur.charAt(0) === '{') { var d = cfParse(cur); d.textEditValue = it.text; val = cfJSON(d); }
          else val = it.text;
          pr.setValue(val, true); set = true;
        }
      }
      if (!set && warn.length < 3) warn.push('Parametro de texto nao encontrado no MOGRT (defina o nome nas opcoes).');
      placed++;
    }
    return cfJSON({ ok: true, placed: placed, warnings: warn });
  } catch (e) { return cfFail(e); }
}

// Faixa de legendas nativa a partir de um .srt
function cf_insertSrt(srtPath, startSec) {
  try {
    var seq = app.project.activeSequence;
    app.project.importFiles([srtPath], true, app.project.getInsertionBin(), false);
    var name = srtPath.replace(/^.*[\\\/]/, ''), item = cfFindItemByName(app.project.rootItem, name);
    if (!item) return cfJSON({ ok: false, error: 'SRT importado nao encontrado no projeto.' });
    seq.createCaptionTrack(item, startSec, Sequence.CAPTION_FORMAT_SUBTITLE);
    return cfJSON({ ok: true });
  } catch (e) { return cfFail(e); }
}
