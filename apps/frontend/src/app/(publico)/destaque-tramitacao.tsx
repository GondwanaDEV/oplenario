// DestaqueTramitacao — o card de destaque (Task 1.3, Fatia A2.1). Porte de portal-cidadao.html:422-490.
//
// Resumo por IA: só o estado `off` HONESTO é portado (:477-480) — sem backend de resumo por IA nesta
// fatia, o corpo com resumo fabricado da tela-fonte NÃO entra (Global Constraints "sem dado falso"). A
// tramitação/autoria/permalink continuam visíveis (só o RESUMO é IA — o resto é fato).

import { AzulejoFaixa } from "@/lib/charts/azulejo-faixa";
import { descreverFaixa } from "@/lib/tramitacao-vista";
import type { MateriaVista } from "@/lib/materia-vista";

export function DestaqueTramitacao({ destaque, ente }: { destaque: MateriaVista; ente: string }) {
  const href = `/portal/casa/${ente}/materias/${destaque.proposicaoId}`;
  return (
    <article className="destaque">
      <div className="destaque-grade">
        <div className="destaque-mat">
          <span className="ref">{destaque.ref}</span>
          <span className="selo-sit">
            <span className="g" aria-hidden="true" />
            {destaque.situacao}
          </span>
          <h3>
            <a href={href}>{destaque.titulo}</a>
          </h3>
          <p className="autoria">
            {destaque.autorTexto ? (
              <>
                Autoria de <b>{destaque.autorTexto}</b>
              </>
            ) : (
              "Autoria não informada."
            )}
          </p>

          <div className="tramitacao">
            <p className="rotulo-faixa">Por onde já passou</p>
            <AzulejoFaixa
              estagios={destaque.estagios}
              rotuloAria={descreverFaixa(destaque.ref, destaque.estagios)}
            />
          </div>

          <p className="permalink">
            <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true">
              <path
                d="M5.5 8.5a2.5 2.5 0 0 0 3.5 0l2-2a2.5 2.5 0 1 0-3.5-3.5l-1 1"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.3"
                strokeLinecap="round"
              />
              <path
                d="M8.5 5.5a2.5 2.5 0 0 0-3.5 0l-2 2a2.5 2.5 0 1 0 3.5 3.5l1-1"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.3"
                strokeLinecap="round"
              />
            </svg>
            {destaque.permalink}
          </p>
        </div>

        <div className="resumo-ia" data-ia="off">
          <div className="resumo-off">
            <svg
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth={2}
              aria-hidden="true"
            >
              <circle cx="12" cy="12" r="9" />
              <path d="M12 8h.01M11 12h1v4h1" />
            </svg>
            <p>
              <b>O resumo em linguagem simples está indisponível agora.</b> Ele é escrito com ajuda de
              IA — volta a aparecer aqui assim que o serviço reconectar. Enquanto isso, o{" "}
              <b>texto oficial</b> da proposição continua disponível abaixo, com toda a tramitação.
            </p>
          </div>
          <div className="resumo-rodape">
            <a className="btn btn-contorno" href={href}>
              Ler o texto completo
            </a>
          </div>
        </div>
      </div>
    </article>
  );
}
