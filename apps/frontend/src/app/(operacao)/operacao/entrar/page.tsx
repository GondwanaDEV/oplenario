"use client";

// ADR-0016 — a porta do console. O operador entra pelo realm da Operação: senha E chave de segurança (a chave
// física, USB/NFC — não a biometria do aparelho). Nenhuma conta de Casa entra por aqui.

import { useSearchParams } from "next/navigation";

const ERROS: Record<string, string> = {
  login: "Não deu para começar o login agora. Tente de novo em instantes.",
  negado: "Este acesso não está ativo na Operação. Fale com quem administra a plataforma.",
};

export default function EntrarNoConsole() {
  const params = useSearchParams();
  const erro = params.get("erro");
  const redirect = params.get("redirect");
  const href = `/api/operacao/login${redirect ? `?redirect=${encodeURIComponent(redirect)}` : ""}`;
  return (
    <section className="op-entrar" aria-labelledby="titulo-entrar">
      <p className="eyebrow">Operação · console</p>
      <h1 id="titulo-entrar">Entrar no console</h1>
      <p>
        O console opera as Câmaras da plataforma: provisiona, acompanha e registra tudo o que a Operação faz. Os
        dados de dentro de cada Câmara não aparecem aqui.
      </p>
      <p>
        Você vai precisar da <b>senha</b> e da sua <b>chave de segurança</b>. Na primeira vez, o sistema pede para
        cadastrar a chave.
      </p>
      {erro && ERROS[erro] && (
        <p className="op-aviso erro" role="alert">{ERROS[erro]}</p>
      )}
      <a className="btn btn-primaria" href={href}>Entrar com senha e chave</a>
    </section>
  );
}
