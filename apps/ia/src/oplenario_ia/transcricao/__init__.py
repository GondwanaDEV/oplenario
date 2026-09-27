"""Transcrição de sessão e atribuição de falas (Faixa A / A.3; §22.6 eixos D/E).

ASR e diarização são PORTAS (como a de inferência): um fake determinístico para testes e CI, e um adaptador
self-host (sherpa-onnx: Whisper + pyannote + TitaNet, ONNX em CPU/GPU) instalado como extra opcional. Quem falou é
decidido pelo CAMINHO C: o grupo de voz recebe o nome de quem tinha a palavra na tribuna — o dado que a Mesa já
registra na plataforma —, sem reconhecimento de voz. Nada daqui sai do cluster: não há LLM externo nesta etapa.
"""
