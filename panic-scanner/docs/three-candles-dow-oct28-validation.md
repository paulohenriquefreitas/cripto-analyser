# Validacao micro de 28/10/2026

Branch feat/three-candles-foundation. Esta etapa adiciona validacao, testes e artefatos; nao altera o detector 1+1, MicroZigZag aprovado, Dow principal, estrategias ou Fibonacci. Nao conecta Dow a Fibonacci.

## Fonte e alcance

Consulta explicita WINZ26 M5 nativo do MetaTrader 5, descricao IBOVESPA MINI, terminal build 6230. Coleta: 28/10/2026 20:39 UTC, ultimo tick do relogio civil do terminal 17:39. Fonte: mt5_reader.read_candles / copy_rates_range. Os primeiros 45 candles fechados coincidem integralmente com a exportacao usada na investigacao anterior da abertura de 28/10.

Isso confirma o contrato consultado e continuidade com a fonte anterior. A captura com os tres vistos nao esta disponivel neste turno, e seus horarios nao foram informados. A correspondencia exata com a fonte e os vistos da imagem original continua NAO CERTIFICADA; foi solicitada identificacao ao usuario. A geometria deste contrato/data mostra queda predominante ate perto de 11:40 e depois recuperacoes e lateralizacao; nao se presume correspondencia com a subida descrita na imagem.

O pregao ainda estava em curso na coleta. A exportacao retornou 104 candles: 103 fechados entre 09:00 e 17:30, mais a barra de 17:35 formando, excluida usando o horario do tick nativo. Cursor da reproducao: 17:35, fechamento da barra 17:30. Horarios abaixo sao civis MT5, sem conversao inferida de fuso. Nao ha cobertura certificada apos 17:35.

Fonte bruta, metadados e SHA-256: frontend/src/features/threecandles/validation/oct28/WINZ26-m5.json e source.json. Fingerprints dos algoritmos em algorithm-fingerprints.json.

## Captura

[Captura completa do trecho disponivel](../../frontend/src/features/threecandles/validation/oct28/session.png): todos os 50 fractais confirmados, cadeia micro ciano e referencia de abertura ambar. O primeiro e o ultimo candle foram verificados dentro do viewport por coordenadas e getVisibleRange; sem lacunas de candles.

Interface React real com resposta de Replay exportada pelo Java interceptada por HTTP. Os OHLC sao nativos MT5; a imagem nao e uma captura da janela do terminal ou da sessao original do usuario. Somente no navegador de teste foi exposto o handle do grafico para fitContent e selecao de janelas, sem editar arquivos de producao. O estado textual da Fibonacci permaneceu identico ao trocar as janelas. Seu desenho, niveis M5/H1 e Dow principal ficaram ocultos somente para legibilidade.

Detalhes do mesmo snapshot, todos os fractais ligados:

- [Inicial 09:00-11:00](../../frontend/src/features/threecandles/validation/oct28/session-initial.png).
- [Central 11:00-14:00](../../frontend/src/features/threecandles/validation/oct28/session-central.png).
- [Direita 14:00-17:35](../../frontend/src/features/threecandles/validation/oct28/session-right.png).

Essas sao janelas tecnicas de inspecao, nao uma identificacao dos vistos.

## Resultado

50 fractais originais, 35 selecionados, 34 segmentos ciano vigentes e 38 versoes no historico. A referencia de abertura ambar e adicional e nao conta como fractal. Os 15 extremos fora da cadeia sao oito fractais em quatro candles duplos, tres fractais menos extremos do mesmo tipo e quatro terminais substituidos.

| Janela tecnica | Intervalo | Fractais | Selecionados | Segmentos com destino na janela |
|---|---|---:|---:|---:|
| initial | 09:00-11:00 | 7 | 7 | 6 |
| central | 11:00-14:00 | 19 | 11 | 11 |
| right | 14:00-17:35 | 24 | 17 | 17 |

Contagens por origem do fractal/destino do segmento, com conhecimento ate 17:35. audit.json lista cada candle OHLC, as avaliacoes estritas, os fractais, suas confirmacoes, participacoes e decisoes. session.json conserva linhas vigentes, versoes anteriores e decisoes. prefixes.json contem todos os 103 snapshots causais. excluded.csv permite consultar a tabela em planilha.

## Segmentos e pontas ainda fora do desenho vigente

A tabela lista exclusoes efetivas, sem supor pontas esperadas na imagem ausente. Nas substituicoes, o segmento existiu no Replay antes do horario de atualizacao e permanece no historico; nao era ausencia de fractal nem falha de renderizacao. Nos extremos do mesmo tipo falta um fractal oposto para formar a pequena oscilacao sem ligar topo a topo/fundo a fundo.

| Formacao | Confirmacao original | Extremo/segmento fora da cadeia atual | Causa e disponibilidade |
|---|---|---|---|
| 11:00 | 11:10 | 11:00 BOTTOM 173785 | Sem fractal oposto confirmado entre extremos do mesmo tipo. Mantido 10:50 BOTTOM 173515. |
| 11:50 | 12:00 | 11:50 TOP 173370 / 11:50 BOTTOM 172765 | Extremo duplo: ordem intrabar desconhecida; os dois fractais permanecem marcados, sem serem usados nas ligacoes. |
| 12:00 | 12:10 | 12:00 BOTTOM 172885 | Sem fractal oposto confirmado entre extremos do mesmo tipo. Mantido 11:40 BOTTOM 172550. |
| 12:40 | 12:50 | 12:40 TOP 173445 | Sem fractal oposto confirmado entre extremos do mesmo tipo. Mantido 12:30 TOP 173565. |
| 13:20 | 13:30 | 13:15 BOTTOM 173180 -> 13:20 TOP 173820 | Substituido por 13:55 TOP 174325 na confirmacao 14:05. Versao anterior preservada no historico. |
| 13:30 | 13:40 | 13:30 TOP 173885 / 13:30 BOTTOM 173290 | Extremo duplo: ordem intrabar desconhecida; os dois fractais permanecem marcados, sem serem usados nas ligacoes. |
| 15:00 | 15:10 | 15:00 TOP 173220 / 15:00 BOTTOM 172225 | Extremo duplo: ordem intrabar desconhecida; os dois fractais permanecem marcados, sem serem usados nas ligacoes. |
| 15:20 | 15:30 | 15:15 TOP 173490 -> 15:20 BOTTOM 172830 | Substituido por 15:30 BOTTOM 172685 na confirmacao 15:40. Versao anterior preservada no historico. |
| 16:15 | 16:25 | 16:05 TOP 174120 -> 16:15 BOTTOM 173430 | Substituido por 16:40 BOTTOM 173210 na confirmacao 16:50. Versao anterior preservada no historico. |
| 16:30 | 16:40 | 16:30 TOP 173825 / 16:30 BOTTOM 173415 | Extremo duplo: ordem intrabar desconhecida; os dois fractais permanecem marcados, sem serem usados nas ligacoes. |
| 16:40 | 16:50 | 16:05 TOP 174120 -> 16:40 BOTTOM 173210 | Substituido por 16:55 BOTTOM 173150 na confirmacao 17:05. Versao anterior preservada no historico. |
| 17:30 | Nao confirmavel neste snapshot | Eventual extremo de 17:30 | Barra direita 17:35 ainda formando. Nenhum fractal futuro e presumido ou prometido. |

Outras barras sem extremo estrito estao registradas como NO_STRICT_FRACTAL em audit.json. Sem a imagem ou as pontas/horarios dos vistos nao e possivel identifica-las como ondas desejadas ausentes. A barra 09:00 e borda sem vizinho esquerdo, tratada pela referencia de abertura independente ja aprovada.

## Politica de precos iguais: avaliacao separada

Nenhuma maxima igual entre vizinhos e nenhuma minima igual entre vizinhos nos 103 candles fechados. Uma politica de plato nao acrescenta candidato neste snapshot e nao resolve os casos acima.

Foi avaliada apenas offline a hipotese de agrupar barras consecutivas com mesmo preco extremo, exigir vizinhos externos estritamente inferiores (topo) ou superiores (fundo), escolher a ultima barra empatada e aguardar o fechamento da primeira barra direita. Resultado: zero grupos empatados e zero candidatos adicionais. Nenhum resultado da hipotese foi enviado ao motor, grafico ou sinais. Relaxar > para >= sem grupos poderia duplicar pivos e nao resolveria a ordem intrabar dos extremos duplos.

Empates de preco entre fractais separados no tempo sao distintos dos empates entre candles vizinhos. A politica de selecao ja aprovada conserva a primeira origem quando fractais consecutivos do mesmo tipo empatam; permaneceu inalterada.

## Verificacao

MicroDowOctober28ValidationTest compara os 103 prefixos incremental/reconstrucao/ThreeCandleReplay, fornecendo deliberadamente toda a memoria futura com corte por instante. Verifica confirmacoes, decisoes, continuidade, repeticao idempotente, ausencia de duplicacoes e reset. O auditor Python recalcula independentemente as desigualdades estritas e exige correspondencia exata com cada fractal e instante de confirmacao exportado.

Um teste explicito compara os 51 fractais originais de 16/10 ao baseline congelado, incluindo extremo, classificacao, comparacoes e motivo. Todos os testes causais existentes de continuidade, abertura, lateralizacao e Dow principal tambem executaram. Resultado: 15 testes Java aprovados; dois condicionais nao executados (exportacao do baseline original e teste historico legado dependente de propriedade). Captura sem erros JavaScript, cobertura completa do viewport e Fibonacci preservada.

JDK 21: mvn -pl panic-scanner -am test -Dtest=MicroDowOctober28ValidationTest,MicroDowContinuityTest,MicroDowOpeningTest,MicroDowEngineTest,DowStructureEngineTest -Dsurefire.failIfNoSpecifiedTests=false. Auditor: python frontend/src/features/threecandles/validation/auditOctober28.py. Captura: node frontend/src/features/threecandles/validation/captureOctober28.mjs, com Vite em 127.0.0.1:5174.

Conclusao: fonte nativa e desenho do trecho disponivel validados. Correspondencia especifica com os tres vistos e trecho posterior a 17:35 ainda nao certificados. Nenhuma alteracao de sensibilidade implementada.
