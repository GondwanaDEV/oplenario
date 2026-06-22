/* ============================================================
   O PLENÁRIO · tema.js — alternância de tema claro / escuro
   Linguagem: "República Luminosa".

   Page-agnóstico: procura um botão #tema-toggle (opcional) e:
     · aplica o tema inicial = escolha salva OU preferência do SO;
     · lembra a escolha em localStorage('oplenario-tema');
     · mantém aria-pressed + o rótulo .tema-rotulo em sincronia.

   Linkar no fim do <body>:  <script src="../sistema/tema.js"></script>
   (de ../componentes.html, usar "sistema/tema.js").
   O CSS é dirigido por [data-tema] — ver ../LINGUAGEM-VISUAL.md.
   ============================================================ */
(function () {
  var root = document.documentElement;
  var btn = document.getElementById('tema-toggle');
  var salvo = null;
  try { salvo = localStorage.getItem('oplenario-tema'); } catch (e) {}
  var inicial = salvo || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'escuro' : 'claro');
  aplicar(inicial);
  function aplicar(t) {
    root.setAttribute('data-tema', t);
    if (btn) {
      btn.setAttribute('aria-pressed', t === 'escuro' ? 'true' : 'false');
      btn.setAttribute('aria-label', t === 'escuro' ? 'Alternar para tema claro' : 'Alternar para tema escuro');
      var rot = btn.querySelector('.tema-rotulo');
      if (rot) rot.textContent = t === 'escuro' ? 'Escuro' : 'Claro';
    }
  }
  if (btn) btn.addEventListener('click', function () {
    var prox = root.getAttribute('data-tema') === 'escuro' ? 'claro' : 'escuro';
    try { localStorage.setItem('oplenario-tema', prox); } catch (e) {}
    aplicar(prox);
  });
})();
