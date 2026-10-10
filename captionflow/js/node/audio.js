// Utilidades de audio via ffmpeg (somente Node / CEP).
(function (root) {
  if (typeof require !== 'function') return; // modo navegador: sem Node
  var CF = (root.CF = root.CF || {});
  var cp = require('child_process');
  var fs = require('fs');
  var path = require('path');

  function exists(p) { try { return fs.statSync(p).isFile(); } catch (e) { return false; } }

  function findFfmpeg(custom) {
    if (custom && exists(custom)) return custom;
    var exe = process.platform === 'win32' ? 'ffmpeg.exe' : 'ffmpeg';
    var cands = [
      path.join(__dirname, '..', '..', 'bin', exe),
      '/opt/homebrew/bin/ffmpeg', '/usr/local/bin/ffmpeg', '/usr/bin/ffmpeg',
      'C:\\ffmpeg\\bin\\ffmpeg.exe', 'C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe'
    ];
    for (var i = 0; i < cands.length; i++) if (exists(cands[i])) return cands[i];
    return exe; // tenta o PATH
  }

  function run(bin, args, onErr) {
    return new Promise(function (resolve, reject) {
      var p = cp.spawn(bin, args, { windowsHide: true }), err = '';
      p.stderr.on('data', function (d) { err += d; if (err.length > 20000) err = err.slice(-20000); });
      p.on('error', function (e) { reject(new Error('Nao foi possivel executar "' + bin + '": ' + e.message + '. Configure o caminho do ffmpeg nas opcoes.')); });
      p.on('close', function (code) { code === 0 ? resolve() : reject(new Error('ffmpeg falhou (' + code + '): ' + err.split('\n').slice(-6).join('\n'))); });
    });
  }

  // Extrai um trecho da midia para arquivo compacto (mp3 mono 16 kHz) ou wav 16 kHz.
  function extract(o) {
    var args = ['-y', '-v', 'error'];
    if (o.start > 0) args.push('-ss', String(o.start));
    args.push('-i', o.input);
    if (o.duration > 0) args.push('-t', String(o.duration));
    args.push('-vn', '-ac', '1', '-ar', '16000');
    if (o.format === 'wav') args.push('-c:a', 'pcm_s16le'); else args.push('-c:a', 'libmp3lame', '-b:a', '48k');
    args.push(o.out);
    return run(o.ffmpeg, args).then(function () { return o.out; });
  }

  // Decodifica PCM 16-bit mono para analise de silencio.
  function decodePCM(o) {
    return new Promise(function (resolve, reject) {
      var args = ['-v', 'error'];
      if (o.start > 0) args.push('-ss', String(o.start));
      args.push('-i', o.input);
      if (o.duration > 0) args.push('-t', String(o.duration));
      args.push('-vn', '-ac', '1', '-ar', String(o.sampleRate || 16000), '-f', 's16le', '-');
      var p = cp.spawn(o.ffmpeg, args, { windowsHide: true }), chunks = [], err = '';
      p.stdout.on('data', function (d) { chunks.push(d); });
      p.stderr.on('data', function (d) { err += d; });
      p.on('error', function (e) { reject(new Error('Nao foi possivel executar ffmpeg: ' + e.message)); });
      p.on('close', function (code) {
        if (code !== 0) return reject(new Error('ffmpeg falhou: ' + err.slice(-300)));
        var buf = Buffer.concat(chunks), n = Math.floor(buf.length / 2), out = new Int16Array(n);
        for (var i = 0; i < n; i++) out[i] = buf.readInt16LE(i * 2);
        resolve({ samples: out, sampleRate: o.sampleRate || 16000 });
      });
    });
  }

  CF.audio = { findFfmpeg: findFfmpeg, extract: extract, decodePCM: decodePCM, exists: exists };
})(typeof window !== 'undefined' ? window : globalThis);
