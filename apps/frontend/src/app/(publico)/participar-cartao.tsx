// O cartão da tela "Entrar para participar" — porte de produto/design-system/o-plenario/telas/entrar-govbr.html
// (arquétipo auth do cidadão, ADR-0015). Apresentacional: a página decide o estado (lib/participar-vista) e este
// componente só pinta. Honesto como o design: consultar é livre; entrar é só para participar; do gov.br a Câmara
// recebe apenas nome e CPF verificado. Sem o broker ligado, o botão NÃO aparece — a tela diz que ainda não está
// disponível em vez de oferecer um login que não existe.

import type { VistaParticipar } from "@/lib/participar-vista";

export function ParticiparCartao({
  vista,
  ente,
  hrefEntrar,
}: {
  vista: VistaParticipar;
  ente: string;
  hrefEntrar: string;
}) {
  return (
    <div className="pt-cartao">
      <span className="pt-selo" aria-hidden="true">
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
          <path d="M12 2a5 5 0 0 0-5 5v3H6a2 2 0 0 0-2 2v8h16v-8a2 2 0 0 0-2-2h-1V7a5 5 0 0 0-5-5z" />
        </svg>
      </span>
      <h1>Entrar para participar</h1>
      <p className="pt-lead">
        Identifique-se com a sua conta gov.br para comentar e acompanhar seus pedidos na{" "}
        {vista.nome ?? "Câmara"}.
      </p>

      <div className="pt-porques">
        <div className="pt-porque">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
          </svg>
          <span>
            <b>Comentar</b> nas matérias em tramitação.
          </span>
        </div>
        <div className="pt-porque">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
            <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0" />
          </svg>
          <span>
            <b>Acompanhar</b> seus pedidos de e-SIC e ouvidoria.
          </span>
        </div>
        <div className="pt-porque">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
            <path d="M5 12l5 5L20 6" />
          </svg>
          <span>
            Sua identidade é <b>verificada</b>, o que dá mais peso à sua manifestação.
          </span>
        </div>
      </div>

      {vista.estado === "pronto" ? (
        <a className="pt-govbr" href={hrefEntrar}>
          Entrar com{" "}
          <span className="pt-wm">
            gov<b>.br</b>
          </span>
        </a>
      ) : (
        <div className="em-breve" role="status">
          <p className="em-breve-titulo">A entrada com gov.br ainda não está disponível nesta Câmara</p>
          <p className="em-breve-motivo">
            A Câmara ainda não ligou a identificação pelo gov.br. Enquanto isso, tudo o que é público continua
            aberto a você, sem cadastro.
          </p>
        </div>
      )}

      <div className="pt-ou">só para participar</div>

      <div className="pt-livre">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
          <circle cx="11" cy="11" r="7" />
          <path d="m21 21-4.3-4.3" />
        </svg>
        <span>
          <b>Consultar é livre.</b> Você não precisa entrar para ver matérias, leis, sessões ou pedir informação
          pública. <a href={`/portal/casa/${ente}`}>Voltar e só consultar →</a>
        </span>
      </div>

      <p className="pt-lgpd">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} aria-hidden="true">
          <path d="M12 3l8 4v5c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7z" />
        </svg>
        <span>
          Ao entrar, a Câmara recebe do gov.br apenas <b>seu nome e CPF verificado</b> — o necessário para
          identificar sua participação. Tratamos seus dados conforme a LGPD.
        </span>
      </p>
    </div>
  );
}
