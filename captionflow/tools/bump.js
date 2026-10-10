// Uso: node tools/bump.js [patch|minor|major]  (padrao: patch)
// Atualiza js/version.js e CSXS/manifest.xml com a nova versao e a data do build.
const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..');
const vp = path.join(root, 'js', 'version.js');
const cur = /version: '(\d+)\.(\d+)\.(\d+)'/.exec(fs.readFileSync(vp, 'utf8')).slice(1).map(Number);
const kind = process.argv[2] || 'patch';
if (kind === 'major') { cur[0]++; cur[1] = 0; cur[2] = 0; } else if (kind === 'minor') { cur[1]++; cur[2] = 0; } else cur[2]++;
const v = cur.join('.'), d = new Date(), p = (n) => String(n).padStart(2, '0');
const build = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
fs.writeFileSync(vp, `window.CF_VERSION = { version: '${v}', build: '${build}' };\n`);
const mp = path.join(root, 'CSXS', 'manifest.xml');
fs.writeFileSync(mp, fs.readFileSync(mp, 'utf8').replace(/(ExtensionBundleVersion|Extension Id="com.joohn.captionflow.panel" Version)="[^"]+"/g, '$1="' + v + '"'));
console.log('CaptionFlow v' + v + ' (' + build + ')');
