# Antecipacao Dow 2+1 para observar o terceiro candle

Implementado em feat/three-candles-foundation. A Dow principal confirmada continua usando 2+2. Nenhum calculo de onda ou 50% passou a depender de candidatos.

## Dois reconhecimentos independentes

1. Fractal informativo 2+1: quatro M5 fechados contiguos A,B,T,C da propria sessao. Topo: maxima de T estritamente superior a A,B,C; fundo: minima estritamente inferior. Igualdade nao passa. No fechamento de C, publica candidato com origem, quatro observacoes e horario de disponibilidade. Nao aguarda o fechamento de D.
2. Padrao operacional ja existente: contato em alvo conhecido, contexto de aproximacao e C2 negando por fechamento. Continua reconhecendo C1/C2 ao fechar C2, mesmo quando um pavio de C2 impede o fractal estrito 2+1. Nao foi introduzido um filtro de fractal obrigatorio nem uma segunda regra de entrada.

A diferenca visual anterior: o modo padrao SETUPS escondia os candidatos sem alcance. Agora MONITORING (Em formacao e gatilhos) mostra os padroes reconhecidos enquanto C3 ainda pode ser acompanhado, mais os gatilhos alcancados. Modos de selecao individual, gatilhos e todos os candidatos continuam disponiveis. Os checkboxes de marcacoes/numeros/destaques mantem suas preferencias.

## Evolucao do candidato

Ao fechar C: CANDIDATE_2_1. Ao fechar D contiguo: CONFIRMED_2_2 quando D preserva o extremo estrito, INVALIDATED quando o iguala/ultrapassa. Se D faltar, o proximo candle encerra como INCONCLUSIVE_GAP. A troca de sessao reinicia o estudo. Historico de candidatos e resolucoes permanece no snapshot da sessao, sem apagar os invalidados. O estado CONFIRMED_2_2 informa o fractal; nao garante selecao como ponta estrutural, pois a engine Dow continua aplicando suas regras, inclusive ambiguidades topo/fundo simultaneos.

## Grafico e auditoria

Com Dow principal habilitada: C1 no extremo, C2 no candle seguinte e C3 no terceiro candle. Marcacoes vermelhas para candidato de topo (alta para baixa), verdes para candidato de fundo (baixa para alta). C3 e desenhado no candle real quando existe observacao disponivel dele, inclusive parcial. Nao se inventa um candle na abertura. Ao resolver o candidato, as marcas provisorias saem; os extremos confirmados mantem C1/C2/C3 com as cores correspondentes da camada principal. Auditoria Candidatos Dow 2+1 apresenta tipo, preco, formacao, conhecimento no fechamento de C, resolucao e motivo; tabela so e montada quando aberta.

[Topo conhecido ao fechar C](../../frontend/src/features/threecandles/validation/dow-anticipation/top-at-c-close.png): WINZ26, 06/10/2026, T as 09:55, C as 10:00, candidato conhecido as 10:05, antes do fechamento de D.

[Fundo com D em formacao](../../frontend/src/features/threecandles/validation/dow-anticipation/bottom-d-forming.png): WINZ26, 30/10/2026, T/F as 09:15, C as 09:20, candidato conhecido as 09:25; corte 09:26 com D parcial de origem 09:25.

Capturas da interface React real com API interceptada pelos snapshots gerados no teste Java, nao de uma sessao do usuario reiniciada. Fontes MT5 nativas congeladas nos testes; horarios do terminal. `captureDowAnticipation.mjs` reproduz as capturas.

## Validacao

Testes Java cobrem disponibilidade somente ao fechar C, D ainda inexistente, confirmacao 2+2 posterior, igualdade/invalidez, fundo simetrico, lacunas, sessao e reconstrucao. Candidatos nao aparecem na memoria de pivots confirmados antes de D. Testes de Dow principal/micro/ambos 50% e detector operacional continuam passando.

Frontend valida causalidade da evidencia 2+1 e da resolucao, desenha amarelo sem depender de HH/HL, nao revela candidatos futuros nem D ainda nao observado. Teste nativo tambem comprova padrao operacional reconhecido sem candidato fractal 2+1 correspondente. Comparacao dos snapshots preserva exatamente a estrutura principal/micro e os 50% da versao anterior, descontando apenas o novo campo de candidatos informativos. Os gatilhos dos conjuntos 06/10 e 30/10 permanecem os mesmos.

O estudo do padrao continua observacional: teste e retorno em C3 precisam de evidencia posterior. Em Replay fechado nao se ve D parcial; para isso e necessario o Replay progressivo com M1 disponivel. M1 fechado limita a observacao ao fechamento do minuto. Nao foi implementado acompanhamento por ticks nem envio de ordens reais.

Reiniciar backend e recriar Replay para receber earlyPivots; atualizar frontend para ver a camada e o novo modo. application.yml nao precisou ser alterado.

As capturas acima documentam a primeira versao, com T?/F? amarelos. A nomenclatura atual C1/C2/C3 e as cores direcionais foram solicitadas posteriormente pelo usuario; isso nao confirma uma reversao nem uma entrada por si so.
