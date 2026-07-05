// EmBreve — em-breve honesto reusável (Task 0.4, Fatia A2.0). Extrai o espírito de LenteJuridico (A1,
// src/app/(interno)/paineis/mesa/lente-juridico.tsx): superfície sem backend real NUNCA mostra dado
// falso — mostra um rótulo + um motivo textual concreto do porquê ainda não existe. `role="status"` +
// `aria-label` anunciam o estado indisponível a leitores de tela (Global Constraints: AA nos 2 temas).

export function EmBreve({ titulo, motivo }: { titulo: string; motivo: string }) {
  return (
    <div className="em-breve" role="status" aria-label={`${titulo}: em breve`}>
      <p className="em-breve-rotulo">Em breve</p>
      <p className="em-breve-titulo">{titulo}</p>
      <p className="em-breve-motivo">{motivo}</p>
    </div>
  );
}
