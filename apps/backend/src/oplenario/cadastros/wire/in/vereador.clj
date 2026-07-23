(ns oplenario.cadastros.wire.in.vereador
  "Representacao EXTERNA de ENTRADA das 4 escritas do cadastro de vereadores (§22.10 wire/in, ADR-0001,
  Onda D Slice 4). `:closed true` recusa campo extra (anti-forja: identidade_id/ente_id NUNCA vem do corpo).
  Datas e legislatura-id sao :string no wire (ISO/UUID) — o adapters/in coage p/ LocalDate/UUID.")

(def CriarVereador
  [:map {:closed true}
   [:nome [:string {:min 1}]]
   [:nome-parlamentar {:optional true} [:maybe :string]]])

(def EditarVereador
  "PATCH parcial: ambos opcionais; o adapters/in exige >=1 presente e :nome nao-branco se presente."
  [:map {:closed true}
   [:nome {:optional true} [:maybe :string]]
   [:nome-parlamentar {:optional true} [:maybe :string]]])

(def RegistrarMandato
  [:map {:closed true}
   [:legislatura-id [:string {:min 1}]]
   [:partido {:optional true} [:maybe :string]]
   [:natureza [:enum "titular" "suplencia"]]
   [:vigencia-inicio [:string {:min 1}]]
   [:vigencia-fim {:optional true} [:maybe :string]]])

(def RegistrarLicenca
  [:map {:closed true}
   [:inicio [:string {:min 1}]]
   [:fim {:optional true} [:maybe :string]]
   [:motivo {:optional true} [:maybe :string]]])

(def ReassumirMandato
  "O campo e' `reassumiu-em` = o dia em que a pessoa VOLTOU A EXERCER, NUNCA 'ultimo dia da licenca'. Duas
   razoes, e as duas importam: (a) e' o que a secretaria da Casa sabe ('ele reassumiu no dia 10'), nao a
   vespera; (b) a aritmetica de janela do kernel e' INCLUSIVA nos dois lados, entao o servidor grava
   `fim = reassumiu-em - 1` — pedir a vespera ao cliente empurraria esse -1 para fora do servidor, onde
   ninguem o testa."
  [:map {:closed true}
   [:reassumiu-em [:string {:min 1}]]])

(def LigarIdentidade
  "Onda D Slice 5 Task 9 — passo (2) do provisionamento (ligar vereador->identidade). `identidade-id` E'
   o corpo de proposito aqui (ao contrario de CriarVereador/etc, onde ref cross-modulo NUNCA vem do corpo):
   esta rota EXISTE pra receber esse id, gated `admin_ente` (ligar identidade e' parte de conceder acesso)."
  [:map {:closed true}
   [:identidade-id [:string {:min 1}]]])
