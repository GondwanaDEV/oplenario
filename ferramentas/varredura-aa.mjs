import pkg from '/home/user/oplenario/e2e/node_modules/@playwright/test/index.js';
const { chromium } = pkg;
import { readdirSync } from 'node:fs';

const TEMA = process.argv[2] || 'escuro';
const telas = readdirSync('/home/user/oplenario/produto/design-system/o-plenario/telas')
  .filter(f => f.endsWith('.html')).map(f => f.replace('.html', ''));

const b = await chromium.launch({
  executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
  args: ['--no-sandbox', '--font-render-hinting=none'],
});

const falhas = [];
for (const t of telas) {
  const pg = await b.newPage({ viewport: { width: 1440, height: 1200 } });
  try {
    await pg.goto(`http://localhost:8799/telas/${t}.html`, { waitUntil: 'networkidle', timeout: 20000 });
    await pg.evaluate((tm) => document.documentElement.setAttribute('data-tema', tm), TEMA);
    await pg.evaluate(() => document.fonts.ready);
    await pg.waitForTimeout(350);

    const r = await pg.evaluate(() => {
      const parse = (s) => {
        const t = String(s);
        // color(srgb a b c) — o que color-mix() devolve; o regex antigo so' via rgb()/rgba()
        // e caia fora, fazendo o compositor pular a camada e reportar o fundo errado.
        const c = t.match(/color\(srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)(?:\s*\/\s*([\d.]+))?/);
        if (c) return [c[1]*255, c[2]*255, c[3]*255, c[4] === undefined ? 1 : +c[4]];
        const m = t.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?/);
        if (!m) return null;
        return [+m[1], +m[2], +m[3], m[4] === undefined ? 1 : +m[4]];
      };
      const lum = ([r, g, b]) => {
        const f = (c) => { c /= 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
        return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
      };
      const cr = (a, b) => {
        const la = lum(a), lb = lum(b), hi = Math.max(la, lb), lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
      };
      // compoe a pilha de fundos ate' opacidade total — com superficie de vidro
      // o texto nao pousa no token, pousa na MISTURA
      const fundoEfetivo = (el) => {
        const camadas = [];
        for (let n = el; n && n !== document.documentElement.parentNode; n = n.parentElement) {
          const cs = getComputedStyle(n);
          const c = parse(cs.backgroundColor);
          if (c && c[3] > 0.001) { camadas.push(c); if (c[3] >= 0.999) break; }
        }
        const base = parse(getComputedStyle(document.body).backgroundColor) || [255, 255, 255, 1];
        let out = base[3] >= 0.999 ? base.slice(0, 3) : [255, 255, 255];
        for (let i = camadas.length - 1; i >= 0; i--) {
          const [r, g, bb, a] = camadas[i];
          out = [a * r + (1 - a) * out[0], a * g + (1 - a) * out[1], a * bb + (1 - a) * out[2]];
        }
        return out.map(Math.round);
      };

      const achados = [];
      for (const el of document.querySelectorAll('*')) {
        const txt = [...el.childNodes].filter(n => n.nodeType === 3 && n.textContent.trim().length > 1);
        if (!txt.length) continue;
        const cs = getComputedStyle(el);
        if (cs.visibility === 'hidden' || cs.display === 'none' || +cs.opacity < 0.5) continue;
        const rc = el.getBoundingClientRect();
        if (rc.width < 4 || rc.height < 4 || rc.left < -2000) continue;
        const fg = parse(cs.color);
        if (!fg || fg[3] < 0.5) continue;
        const bg = fundoEfetivo(el);
        const px = parseFloat(cs.fontSize);
        const peso = parseInt(cs.fontWeight, 10) || 400;
        const grande = px >= 24 || (px >= 18.66 && peso >= 700);
        const piso = grande ? 3.0 : 4.5;
        const razao = cr(fg.slice(0, 3), bg);
        if (razao >= piso) continue;
        const cls = typeof el.className === 'string' && el.className ? '.' + el.className.trim().split(/\s+/).join('.') : '';
        achados.push({
          sel: el.tagName.toLowerCase() + cls,
          txt: txt[0].textContent.trim().slice(0, 42),
          fg: `rgb(${fg.slice(0, 3).join(',')})`,
          bg: `rgb(${bg.join(',')})`,
          px: Math.round(px), peso,
          razao: +razao.toFixed(2), piso,
        });
      }
      return achados;
    });
    for (const f of r) falhas.push({ tela: t, ...f });
  } catch (e) { console.log(`ERRO ${t}: ${e.message}`); }
  await pg.close();
}
await b.close();

// agrupa por causa (seletor + par de cores), nao por ocorrencia
const grupos = {};
for (const f of falhas) {
  const k = `${f.sel} | ${f.fg} sobre ${f.bg} | ${f.razao}:1 (piso ${f.piso}) | ${f.px}px/${f.peso}`;
  (grupos[k] ||= { telas: new Set(), ex: f.txt }).telas.add(f.tela);
}
const ord = Object.entries(grupos).sort((a, b) => b[1].telas.size - a[1].telas.size);
console.log(`TEMA ${TEMA.toUpperCase()} — ${falhas.length} ocorrencias, ${ord.length} causas distintas\n`);
for (const [k, v] of ord) {
  console.log(`${String(v.telas.size).padStart(3)} telas  ${k}`);
  console.log(`          "${v.ex}"   ${[...v.telas].slice(0, 5).join(' ')}${v.telas.size > 5 ? ' …' : ''}`);
}
