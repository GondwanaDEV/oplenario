// Auditoria exploratoria de UI/UX sobre as telas do design-system, renderizadas.
//
// Nao substitui olhar a tela — mede o que e' MEDIVEL, para que o olho sobre para o que
// so' o olho pega. Cada dimensao aqui saiu de um defeito que ja' apareceu neste projeto
// ou de um piso normativo explicito.
//
//   uso:  node ferramentas/auditoria-ux.mjs [claro|escuro] [largura]
//   servidor: python3 -m http.server 8799 em produto/design-system/o-plenario/

import pkg from '/home/user/oplenario/e2e/node_modules/@playwright/test/index.js';
const { chromium } = pkg;
import { readdirSync } from 'node:fs';

const TEMA = process.argv[2] || 'claro';
const LARGURA = parseInt(process.argv[3] || '1440', 10);
const telas = readdirSync('/home/user/oplenario/produto/design-system/o-plenario/telas')
  .filter((f) => f.endsWith('.html')).map((f) => f.replace('.html', ''));

const b = await chromium.launch({
  executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
  args: ['--no-sandbox', '--font-render-hinting=none'],
});

const achados = [];
for (const t of telas) {
  const pg = await b.newPage({ viewport: { width: LARGURA, height: 1100 } });
  try {
    await pg.goto(`http://localhost:8799/telas/${t}.html`, { waitUntil: 'networkidle', timeout: 25000 });
    await pg.evaluate((tm) => document.documentElement.setAttribute('data-tema', tm), TEMA);
    await pg.evaluate(() => document.fonts.ready);
    await pg.waitForTimeout(350);

    const r = await pg.evaluate(() => {
      const out = [];
      const add = (dim, detalhe, sel) => out.push({ dim, detalhe, sel });
      const nome = (el) => el.tagName.toLowerCase() +
        (typeof el.className === 'string' && el.className ? '.' + el.className.trim().split(/\s+/)[0] : '');
      const visivel = (el) => {
        const cs = getComputedStyle(el), r = el.getBoundingClientRect();
        return cs.display !== 'none' && cs.visibility !== 'hidden' && +cs.opacity > 0.1 &&
               r.width > 0 && r.height > 0 && r.left > -2000;
      };

      // ---- A. ALVO DE TOQUE. O proprio chassi declara min-height:44px no .btn; o piso
      //         normativo (WCAG 2.5.8) e' 24px. Reporto abaixo de 24 como falha dura.
      const emLinhaDeTexto = (el) => {
        // 2.5.8 isenta o alvo "inline": link dentro de uma frase, onde o entorno e' texto.
        const p = el.parentElement; if (!p) return false;
        if (!/^(P|LI|SPAN|TD|DD|BLOCKQUOTE|LABEL|SMALL|EM|STRONG)$/.test(p.tagName)) return false;
        return (p.textContent || '').trim().length > (el.textContent || '').trim().length + 8;
      };
      const alvoDoEnvoltorio = (el) => {
        // input sem padding proprio dentro de um campo estilizado: o alvo e' o envoltorio.
        for (let n = el.parentElement, i = 0; n && i < 3; n = n.parentElement, i++) {
          const r = n.getBoundingClientRect();
          if (Math.min(r.width, r.height) >= 24 && getComputedStyle(n).cursor !== 'auto') return true;
          if (n.tagName === 'LABEL' && Math.min(r.width, r.height) >= 24) return true;
        }
        return false;
      };
      for (const el of document.querySelectorAll('a[href],button,input:not([type=hidden]),select,textarea,[role=button],[tabindex]:not([tabindex="-1"])')) {
        if (!visivel(el)) continue;
        const r = el.getBoundingClientRect();
        if (Math.min(r.width, r.height) >= 24) continue;
        if (emLinhaDeTexto(el)) continue;
        if (alvoDoEnvoltorio(el)) continue;
        const tipo = el.tagName === 'INPUT' ? (el.type || 'text') : el.tagName.toLowerCase();
        add('alvo-de-toque', `${tipo} ${Math.round(r.width)}x${Math.round(r.height)}`, nome(el));
      }

      // ---- B. NOME ACESSIVEL. Controle sem rotulo e' invisivel para leitor de tela.
      for (const el of document.querySelectorAll('a[href],button,[role=button],input:not([type=hidden])')) {
        if (!visivel(el)) continue;
        const txt = (el.innerText || el.textContent || '').trim();
        const rot = el.getAttribute('aria-label') || el.getAttribute('title') ||
                    (el.getAttribute('aria-labelledby') ? 'ref' : '') ||
                    (el.id && document.querySelector(`label[for="${CSS.escape(el.id)}"]`) ? 'label' : '') ||
                    (el.closest('label') ? 'label' : '') ||
                    el.getAttribute('placeholder') || '';
        if (!txt && !rot) add('sem-nome-acessivel', el.outerHTML.slice(0, 70), nome(el));
      }

      // ---- C. HIERARQUIA DE TITULOS. Sem h1 a tela nao tem raiz; nivel pulado quebra
      //         a navegacao por cabecalho, que e' como leitor de tela "escaneia".
      const hs = [...document.querySelectorAll('h1,h2,h3,h4,h5,h6')].filter(visivel);
      if (!hs.length) add('titulos', 'nenhum titulo visivel', 'documento');
      else {
        if (!hs.some((h) => h.tagName === 'H1')) add('titulos', 'sem h1', 'documento');
        let ant = 0;
        for (const h of hs) {
          const n = +h.tagName[1];
          if (ant && n > ant + 1) add('titulos', `pulou h${ant} -> h${n} ("${h.innerText.trim().slice(0, 30)}")`, 'documento');
          ant = n;
        }
      }

      // ---- D. IMAGEM SEM ALTERNATIVA
      for (const el of document.querySelectorAll('img')) {
        if (!visivel(el)) continue;
        if (el.getAttribute('alt') === null) add('img-sem-alt', el.getAttribute('src') || '?', nome(el));
      }

      // ---- E. ESCALA TIPOGRAFICA. O sistema declara 12/13/16/21/28/40/56. Tamanho fora
      //         dela e' decisao solta — some o sentido de ter escala.
      // 11 entrou na escala em 23/09/2026 (ver tokens.css). Tamanho FLUIDO por clamp()
      // cai entre degraus por desenho, entao so' interessa o que e' FIXO fora da escala.
      const ESCALA = [11, 12, 13, 16, 21, 28, 40, 56];
      const impressa = document.body.className.includes('folha') ||
                       !!document.querySelector('style, link') && /width: *min\(\d+mm/.test(document.head.innerHTML);
      const fora = new Map();
      for (const el of document.querySelectorAll('*')) {
        if (!visivel(el)) continue;
        const temTexto = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim().length > 1);
        if (!temTexto) continue;
        const px = Math.round(parseFloat(getComputedStyle(el).fontSize));
        if (!ESCALA.some((v) => Math.abs(v - px) <= 1)) {
          fora.set(px, (fora.get(px) || 0) + 1);
        }
      }
      for (const [px, n] of fora) add('fora-da-escala-tipografica', `${n} elementos`, `${px}px`);

      // ---- F. MEDIDA DE LINHA. Acima de ~80 caracteres o olho perde a linha no retorno.
      for (const el of document.querySelectorAll('p,li,dd,blockquote')) {
        if (!visivel(el)) continue;
        const txt = (el.innerText || '').trim();
        if (txt.length < 120) continue;
        const cs = getComputedStyle(el);
        // `ch` medido DE VERDADE, nao estimado como 0.5em: a largura do "0" varia por
        // familia (nesta e' ~0.6em), e a estimativa antiga acusava 86 onde havia 72.
        const sonda = document.createElement('span');
        sonda.style.cssText = 'position:absolute;visibility:hidden;white-space:pre';
        sonda.style.font = cs.font; sonda.textContent = '0';
        el.appendChild(sonda);
        const larguraCh = sonda.getBoundingClientRect().width || parseFloat(cs.fontSize) * 0.5;
        sonda.remove();
        const ch = el.getBoundingClientRect().width / larguraCh;
        if (ch > 85) add('medida-de-linha', `~${Math.round(ch)} caracteres`, nome(el));
      }

      // ---- G. ROLAGEM HORIZONTAL. Em largura de telefone e' defeito de layout, nunca escolha.
      if (document.documentElement.scrollWidth > window.innerWidth + 2) {
        const culpados = [...document.querySelectorAll('*')].filter((el) => {
          const r = el.getBoundingClientRect();
          return visivel(el) && r.right > window.innerWidth + 2 && r.width > 40;
        }).slice(0, 3).map(nome);
        add('rolagem-horizontal', `${document.documentElement.scrollWidth}px de conteudo`, culpados.join(' ') || '?');
      }

      // ---- H. TEXTO CORTADO por overflow escondido.
      for (const el of document.querySelectorAll('*')) {
        if (!visivel(el)) continue;
        const cs = getComputedStyle(el);
        if (cs.overflow === 'visible' || cs.textOverflow === 'ellipsis') continue;
        // .sr-only e' recortado DE PROPOSITO (clip/1x1) — e' tecnica, nao defeito.
        if (cs.clip !== 'auto' || cs.clipPath !== 'none' || (el.clientWidth <= 2 && el.clientHeight <= 2)) continue;
        // textarea e campo com rolagem propria nao "cortam": rolam.
        if (/^(TEXTAREA|SELECT)$/.test(el.tagName) || cs.overflowY === 'auto' || cs.overflowY === 'scroll') continue;
        const temTexto = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim().length > 3);
        if (!temTexto) continue;
        if (el.scrollWidth > el.clientWidth + 3 || el.scrollHeight > el.clientHeight + 3) {
          add('texto-cortado', `${el.scrollWidth}x${el.scrollHeight} em ${el.clientWidth}x${el.clientHeight}`, nome(el));
        }
      }
      return out;
    });
    for (const a of r) achados.push({ tela: t, ...a });
  } catch (e) { console.log(`ERRO ${t}: ${e.message}`); }
  await pg.close();
}
await b.close();

// ---- relatorio agrupado por CAUSA, nao por ocorrencia ----
const porDim = {};
for (const a of achados) (porDim[a.dim] ||= []).push(a);
console.log(`\n=== AUDITORIA UX · tema ${TEMA} · ${LARGURA}px · ${telas.length} telas ===`);
console.log(`${achados.length} ocorrencias em ${Object.keys(porDim).length} dimensoes\n`);
for (const [dim, lista] of Object.entries(porDim).sort((a, b) => b[1].length - a[1].length)) {
  const porSel = {};
  for (const a of lista) (porSel[`${a.sel} | ${a.detalhe}`] ||= new Set()).add(a.tela);
  const chaves = Object.entries(porSel).sort((a, b) => b[1].size - a[1].size);
  console.log(`## ${dim} — ${lista.length} ocorrencias, ${chaves.length} formas`);
  for (const [k, telasSet] of chaves.slice(0, 6)) {
    console.log(`   ${String(telasSet.size).padStart(3)}x  ${k}`);
    console.log(`        ${[...telasSet].slice(0, 4).join(' ')}${telasSet.size > 4 ? ' …' : ''}`);
  }
  if (chaves.length > 6) console.log(`   … e mais ${chaves.length - 6} formas`);
  console.log('');
}
