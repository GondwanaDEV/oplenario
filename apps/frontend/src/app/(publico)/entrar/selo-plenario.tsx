// Selo decorativo compartilhado das telas /entrar e /entrar/[ente] — a marca "O Plenário" (4 quadrantes
// nas cores da paleta), a MESMA arte do `.sig` do rodapé de login.html:145 e do brasão-fallback usado em
// outras telas do design system. Cores FIXAS (hex), não `var(--tokens)`: é heráldica/marca decorativa,
// mesmo racional do `BrasaoGenerico` em barra-institucional.tsx — não tematiza por design.
//
// Decorativo em ambos os usos desta pasta (o heading ao lado já comunica o contexto) -> aria-hidden.

export function SeloPlenario({ tamanho = 34, className }: { tamanho?: number; className?: string }) {
  return (
    <svg
      className={className}
      width={tamanho}
      height={tamanho}
      viewBox="0 0 34 34"
      aria-hidden="true"
    >
      <rect width="34" height="34" rx="9" fill="#0C5340" />
      <rect x="7" y="7" width="9" height="9" rx="2" fill="#D9542B" />
      <rect x="18" y="7" width="9" height="9" rx="2" fill="#1E5FA8" />
      <rect x="7" y="18" width="9" height="9" rx="2" fill="#E8B23A" />
      <rect x="18" y="18" width="9" height="9" rx="2" fill="#16785C" />
    </svg>
  );
}
