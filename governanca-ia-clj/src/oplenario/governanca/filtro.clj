(ns oplenario.governanca.filtro
  "Orquestração do filtro na porta (B1–B4): gate -> (degrada se bloqueado) -> redige -> audita -> chama.
  É o CHOKEPOINT ÚNICO (B2). Nunca envia parcial em silêncio, nunca falha em silêncio (B3)."
  (:require [oplenario.governanca.provenancia :as prov]
            [oplenario.governanca.redator :as red]
            [oplenario.governanca.auditoria :as aud]
            [oplenario.governanca.porta :as porta]))

(defn- hash-payload [pecas] (format "%08x" (hash (mapv :texto pecas))))

(defn chamar-com-governanca
  "Aplica o filtro e (se liberado) chama a porta. Devolve {:status :enviado|:degradado ...}.
  agora = relógio injetado (determinístico)."
  [pecas porta-vendor agora]
  (let [{:keys [liberadas bloqueadas]} (prov/gate pecas)
        bloqueadas+motivo (mapv #(assoc % :motivo (prov/motivo-bloqueio %)) bloqueadas)
        vendor (porta/nome-vendor porta-vendor)
        phash (hash-payload pecas)]
    (if (seq bloqueadas)
      ;; B3: qualquer peça sigilosa -> degrada explicitamente, NÃO envia parcial, audita o bloqueio.
      {:status :degradado
       :motivo "conteudo sigiloso presente — processamento por IA indisponivel"
       :auditoria (aud/registro {:decisao :bloqueado :vendor vendor :liberadas liberadas
                                 :bloqueadas bloqueadas+motivo :redacoes {} :payload-hash phash :agora agora})}
      ;; tudo público: redige PII residual (B2), audita, chama a porta.
      (let [redigidas (mapv #(merge % (red/redigir (:texto %))) liberadas)
            redacoes  (apply merge-with + {} (map :redacoes redigidas))
            payload   (mapv :texto redigidas)]
        {:status :enviado
         :resposta (porta/inferir porta-vendor payload)
         :auditoria (aud/registro {:decisao :liberado :vendor vendor :liberadas liberadas
                                   :bloqueadas [] :redacoes redacoes :payload-hash phash :agora agora})}))))
