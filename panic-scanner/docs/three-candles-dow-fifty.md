# 50% derivado de ondas Dow principais

## Lacuna documentada antes da implementacao

DowStructureEngine.Movement descreve uma aresta selecionada, nao uma onda explicitamente encerrada. TERMINAL_EXTENDED pode substituir a ultima aresta. Nao existe WAVE_COMPLETED no contrato atual.

Definicao conservadora desta modalidade experimental: A->B e selada apenas quando STRUCTURE_PIVOT_SELECTED seleciona um novo extremo oposto C e informa A->B em affectedMovementId. A confirmacao de C e o instante de reconhecimento da conclusao de A->B, mesmo se C for ampliado posteriormente. O ultimo segmento desenhado nao e usado como onda concluida. Nenhum detector ou regra de selecao do Dow sera modificado.

O observador independente documenta a inferencia SEALED_BY_NEXT_STRUCTURAL_PIVOT_V1, com ID do evento de origem e ID do terceiro pivo. Assim, conclusao da onda, confirmacao de B e formacao dos extremos sao tempos distintos. Todos permanecem auditaveis.

O Dow principal existente e o observador M5 estrito 2+2. Nao se infere que seja H1; micro 1+1, abertura provisoria, representantes agrupados e Fibonacci experimental antiga nao sao entradas.

## Implementacao

DowFiftyEngine e um observador independente e somente de leitura. Consome apenas DowStructureEngine.Snapshot com criterio principal, eventos STRUCTURE_PIVOT_SELECTED e IDs originais de pivos symbol:5:timestamp:TOP/BOTTOM. Exige tres extremos confirmados A, B e C, cronologia crescente e preco direcional valido. Consulta affectedMovementId para obter A->B diretamente do motor, sem recalcular a selecao. Nenhuma entrada vem de micro, abertura, agrupamento M5, H1 ou Fibonacci antiga/manual.

A maxima e minima sao os precos dos dois extremos da onda estrutural fornecida pelo Dow, nao representantes nem uma varredura futura por todo o pregao. nivel50=(maxima+minima)/2. O evento de C sela a onda A->B. Uma ampliacao posterior de C nao muda retrospectivamente o instante em que A->B foi selada. O ID da onda, ambos os extremos completos, reconhecimento, ID do evento de origem e ID de C acompanham cada referencia.

Se houver mais de uma conclusao no mesmo instante ou faltar uma prova valida, o observador registra AMBIGUOUS_COMPLETION, encerra a referencia anterior e nao escolhe um alvo substituto por conveniencia. Nao reativa uma onda antiga. Fora desse caso, o evento unico mais recente substitui a referencia vigente, com justificativa no evento REFERENCE_ENDED. Nao ha regra de distancia minima, agrupamento ou entrada operacional.

O contrato ganha dowFifty em Replay e live M5. Reconexao reconstroi o mesmo observador a partir do prefixo fechado da mesma sessao. M15 nao fornece essa modalidade. Nao foi adicionado armazenamento em banco: o historico derivado e completo e reconstituivel sobre o historico de candles conhecido.

## Ciclo de vida e hipoteses

Estados serializados: AGUARDANDO_ONDA, ONDA_CONFIRMADA, MONITORANDO_50, ROMPIMENTO_EM_AVALIACAO, REJEICAO_EM_AVALIACAO e ONDA_ENCERRADA. ONDA_CONFIRMADA e MONITORANDO_50 ficam registrados sequencialmente no instante de ativacao. Cada candle gera uma observacao, inclusive enquanto nao existe referencia. Referencias encerradas continuam na auditoria, mas currentWaveId so identifica a referencia ativa e observe consulta apenas ela.

No fechamento que revela C, primeiro e observada a referencia que ja era conhecida na abertura daquele candle; depois A->B e reconhecida e ativada. A nova linha nunca explica retroativamente o toque ou rompimento dentro desse mesmo candle. O monitor so usa barras fechadas abertas quando a referencia ja estava conhecida.

Leituras explicitamente experimentais, comparadas em paralelo:

- Aproximacao: distancia entre intervalo high/low fechado e 50%, com tolerancia independente de 5 pontos. O construtor permite outras tolerancias; o teste compara 0 e 5 sem mudar onda ou nivel. A tolerancia da Fibonacci antiga nao alimenta este monitor.
- Primeiro toque: primeiro intervalo fechado que contem 50%, uma vez por referencia. Nao infere a ordem intrabar.
- Tentativa de rompimento: penetracao do intervalo no lado oposto ao fechamento anterior. O lado se baseia no fechamento conhecido anterior em relacao a 50%, nao em presumir visita ao topo/fundo.
- Rompimento candidato e confirmacao EXPE?RIMENTAL_M5_CLOSE: fechamento que cruza estritamente os 50%. A mesma barra fechada fornece a confirmacao experimental. Nao e uma confirmacao definitiva de estrategia nem um sinal. Fechamento posterior de volta ao lado original invalida a hipotese e registra falso rompimento experimental.
- Rejeicao candidata: hipotese geometrica RANGE_TOUCH_CLOSE_SAME_SIDE, toque e fechamento no mesmo lado. Continua CANDIDATE_UNVALIDATED; nao ha confirmacao definitiva por pavio, quantidade de candles ou regra de rejeicao introduzida por esta tarefa. Fechar no lado oposto a invalida.
- Do lado do topo, rompimento para baixo observa fundo como possivel destino; retorno observa topo. Do lado do fundo, rompimento para cima observa topo; retorno observa fundo. Sao referencias para avaliar, nao destinos garantidos.
- Chegada a extremo: intervalo de candle posterior a criacao da hipotese alcanca o preco considerado. RETURN_DESTINATION_OBSERVED continua TARGET_OBSERVED_UNVALIDATED: observar o retorno nao valida causalmente uma regra de rejeicao. Se ambos os extremos aparecem na mesma barra, nao se atribui uma ordem interna. Um destino observado e um fechamento que invalida na mesma barra tampouco definem execucao ou lucro.

observations registra tempo da barra, disponibilidade, onda, estado, fechamento e distancias absolutas do fechamento ate 50%, topo e fundo. events conserva aproximacoes, tentativas, candidatas, confirmacoes experimentais, invalidacoes, extremos observados e substituicoes com motivo. Prefixos completos permitem consultar todas as mudancas sem sobrescrever decisoes passadas.

Nao existem ordens, entradas, resultado financeiro, Alvo -> Nega -> Testa ou novas dependencias da estrategia.

## Desenho e auditoria

Controle independente Mostrar 50% Dow principal. A modalidade desenha somente a referencia corrente: horizontal branca continua 2,5 px e etiqueta DOW 50% - preco. Nao desenha tres niveis Fibonacci e nao contribui para autoscale. As ferramentas antiga e manual conservam seus controles e calculos independentes e podem ser usadas para comparacao; nas capturas o desenho antigo foi ocultado para isolar a linha nova.

Atualizacao visual: a linha agora nasce no centro projetado entre os extremos da onda, mas so e exibida a partir do instante de reconhecimento. Ver [Replay progressivo e origem visual](three-candles-progressive-replay.md). A descricao abaixo registra o desenho anterior, preservado nas capturas originais: o inicio da linha era o instante de reconhecimento, nunca a formacao de um pivo. Quando esse instante coincide com o fechamento do ultimo candle, o grafico ainda nao tem uma barra na proxima posicao: usa-se a coordenada logica nativa da proxima posicao M5, sem deslocar a origem para uma barra anterior. A extensao horizontal a direita e projecao de um nivel ja conhecido, nao uso de precos futuros. Fora do viewport ou quando nao ha onda conhecida, nao usa formacao como confirmacao. Horarios de confirmacao live nao alinhados ao inicio de uma barra usam posicao logica fracionaria entre timestamps conhecidos, permanecendo estaveis quando chegam barras posteriores.

Clicar na linha seleciona sua onda na auditoria. A ficha mostra IDs, extremos, precos, formacao e confirmacao, maxima/minima, 50%, reconhecimento, estado, evento de origem e ultima reacao. Topo/fundo sao mostrados como referencias possiveis. O seletor de historico inspeciona ondas antigas sem alterar currentWaveId ou reativar um monitor encerrado. O contrato do frontend rejeita onda fabricada, terminal ainda nao selado, calculo incorreto, evento de origem ausente, conclusao futura ou reativacao de referencia encerrada.

## Fontes e cortes fixados

WINZ26 28/10/2026: copia nativa MT5 ja validada em ../frontend/src/features/threecandles/validation/oct28/WINZ26-m5.json. Mantido exatamente o corte 17:35, com 103 candles fechados 09:00-17:30. A barra 17:35 formando e qualquer dado posterior nao entram. O estudo nao amplia esse corte com uma nova consulta ao terminal.

WIN$N 16/10/2026: cache separado historical-data/WIN$N/three-candles-m5/2026-10-16.json, seriesKind MT5_CONTINUOUS_UNMAPPED, recuperado em 27/10/2026. Copia em validation/dow-fifty/WIN_N-2026-10-16.json. Nao foi convertido nem renomeado a partir de WINZ26. Sao 113 barras fechadas do dia, ultimo fechamento disponivel 18:25. Horarios civis MT5, sem deslocamento automatico de fuso.

O resultado contem 17 ondas seladas WINZ26 e 21 WIN$N. Em WINZ26, a primeira e a alta do fundo 177.010 de 09:15 (confirmado 09:30) ao topo 177.710 de 09:30 (confirmado 09:45). O novo fundo de 09:35 confirma em 09:50, sela a onda anterior e autoriza 50%=177.360. As 09:45 existia o segmento, mas nenhuma onda concluida para este criterio; a linha nova nao estava visivel.

Em WIN$N, a primeira referencia e a baixa do topo 209.375 formado 09:25 ao fundo 208.675 formado 09:30; 50%=209.025. So fica reconhecida 10:00, quando chega o proximo extremo estrutural oposto confirmado.

## Capturas e sequencia de preco

As capturas usam frontend React real e respostas Java exportadas por instante com HTTP interceptado. Fonte, horarios e contratos sao separados. O desenho principal esta ligado; micro, referencias H1/M5 agrupadas e Fibonacci antiga estao ocultos. Sao verificadas etiqueta branca de 2,5 px, clique para auditoria, autoscale nativo e preservacao do estado Fibonacci. Nao representam uma sessao de ordens.

| Fonte/cursor | Evidencia |
|---|---|
| WINZ26 09:45 | [WINZ26-waiting](../../frontend/src/features/threecandles/validation/dow-fifty/WINZ26-waiting.png) |
| WINZ26 09:50 | [WINZ26-confirmed](../../frontend/src/features/threecandles/validation/dow-fifty/WINZ26-confirmed.png) |
| WINZ26 10:00 | [WINZ26-touch](../../frontend/src/features/threecandles/validation/dow-fifty/WINZ26-touch.png) |
| WINZ26 10:10 | [WINZ26-false-break](../../frontend/src/features/threecandles/validation/dow-fifty/WINZ26-false-break.png) |
| WINZ26 10:15 | [WINZ26-replaced](../../frontend/src/features/threecandles/validation/dow-fifty/WINZ26-replaced.png) |
| WINZ26 17:35 | [WINZ26-final](../../frontend/src/features/threecandles/validation/dow-fifty/WINZ26-final.png) |
| WIN$N 10:00 | [WIN_N-confirmed](../../frontend/src/features/threecandles/validation/dow-fifty/WIN_N-confirmed.png) |
| WIN$N 16:40 | [WIN_N-false-break](../../frontend/src/features/threecandles/validation/dow-fifty/WIN_N-false-break.png) |
| WIN$N 18:25 | [WIN_N-final](../../frontend/src/features/threecandles/validation/dow-fifty/WIN_N-final.png) |

Exemplo cronologico da primeira referencia WINZ26:

| Conhecido em | Observacao | Fechamento M5 | Decisao |
|---|---|---:|---|
| 09:45 | A->B ainda terminal | 176.995 | AGUARDANDO_ONDA; nenhum 50% Dow |
| 09:50 | C confirma e sela A->B | 177.215 | Ativar 177.360; nao observar retroativamente a barra confirmadora |
| 10:00 | Primeiro toque e fechamento cruzando de baixo para cima | 177.410 | Rompimento confirmado apenas pelo criterio experimental M5; possivel topo 177.710 |
| 10:05 | Novo toque e fechamento do lado superior | 177.605 | Retorno candidato nao validado |
| 10:10 | Fechamento retorna abaixo de 177.360 | 177.175 | Invalidar rompimento anterior e retorno pendente; observar novo rompimento experimental para baixo |
| 10:15 | Intervalo atinge fundo; chega nova onda selada | 176.745 | Registrar extremo, encerrar referencia antiga e ativar 177.267,50 |

A mesma barra pode invalidar uma hipotese anterior e originar uma candidata na direcao contraria. Sao eventos ordenados de leitura de fechamento, nao duas operacoes. Contagens experimentais de falso rompimento: cinco no corte WINZ26 e tres no corte WIN$N; nao sao taxa de acerto nem resultados operacionais.

## Cronologia completa das ondas e referencias

Em cada linha, a confirmacao de conclusao ocorre no evento do terceiro pivo, nao nas origens A/B. A coluna de encerramento informa a chegada da proxima onda selada. A decisao de atualizacao e sempre explicita: primeira onda unica ou substituicao pela mais recente unica; ambiguidades nao selecionam alvo.

### WINZ26

| Reconhecida | Onda (origens A -> B) | Direcao | Maxima | Minima | 50% | Referencia encerrada |
|---|---|---|---:|---:|---:|---|
| 09:50 | 09:15 -> 09:30 | UP | 177.710,00 | 177.010,00 | 177.360,00 | 10:15 |
| 10:15 | 09:30 -> 09:35 | DOWN | 177.710,00 | 176.825,00 | 177.267,50 | 11:05 |
| 11:05 | 09:35 -> 10:00 | UP | 177.690,00 | 176.825,00 | 177.257,50 | 11:20 |
| 11:20 | 10:00 -> 10:50 | DOWN | 177.690,00 | 173.515,00 | 175.602,50 | 11:55 |
| 11:55 | 10:50 -> 11:05 | UP | 174.735,00 | 173.515,00 | 174.125,00 | 12:05 |
| 12:05 | 11:05 -> 11:40 | DOWN | 174.735,00 | 172.550,00 | 173.642,50 | 12:40 |
| 12:40 | 11:40 -> 12:15 | UP | 174.060,00 | 172.550,00 | 173.305,00 | 14:10 |
| 14:10 | 12:15 -> 12:25 | DOWN | 174.060,00 | 172.965,00 | 173.512,50 | 14:55 |
| 14:55 | 12:25 -> 13:55 | UP | 174.325,00 | 172.965,00 | 173.645,00 | 15:30 |
| 15:30 | 13:55 -> 15:00 | DOWN | 174.325,00 | 172.225,00 | 173.275,00 | 15:45 |
| 15:45 | 15:00 -> 15:15 | UP | 173.490,00 | 172.225,00 | 172.857,50 | 15:55 |
| 15:55 | 15:15 -> 15:30 | DOWN | 173.490,00 | 172.685,00 | 173.087,50 | 16:15 |
| 16:15 | 15:30 -> 15:40 | UP | 174.250,00 | 172.685,00 | 173.467,50 | 16:20 |
| 16:20 | 15:40 -> 16:00 | DOWN | 174.250,00 | 173.130,00 | 173.690,00 | 16:30 |
| 16:30 | 16:00 -> 16:05 | UP | 174.120,00 | 173.130,00 | 173.625,00 | 17:15 |
| 17:15 | 16:05 -> 16:55 | DOWN | 174.120,00 | 173.150,00 | 173.635,00 | 17:25 |
| 17:25 | 16:55 -> 17:00 | UP | 174.235,00 | 173.150,00 | 173.692,50 | Vigente no corte |

### WIN$N

| Reconhecida | Onda (origens A -> B) | Direcao | Maxima | Minima | 50% | Referencia encerrada |
|---|---|---|---:|---:|---:|---|
| 10:00 | 09:25 -> 09:30 | DOWN | 209.375,00 | 208.675,00 | 209.025,00 | 10:25 |
| 10:25 | 09:30 -> 10:00 | UP | 209.690,00 | 208.675,00 | 209.182,50 | 10:55 |
| 10:55 | 10:00 -> 10:10 | DOWN | 209.690,00 | 208.640,00 | 209.165,00 | 11:05 |
| 11:05 | 10:10 -> 10:40 | UP | 210.210,00 | 208.640,00 | 209.425,00 | 11:15 |
| 11:15 | 10:40 -> 10:50 | DOWN | 210.210,00 | 209.310,00 | 209.760,00 | 12:00 |
| 12:00 | 10:50 -> 11:20 | UP | 210.535,00 | 209.310,00 | 209.922,50 | 12:10 |
| 12:10 | 11:20 -> 11:45 | DOWN | 210.535,00 | 208.340,00 | 209.437,50 | 12:35 |
| 12:35 | 11:45 -> 11:55 | UP | 209.680,00 | 208.340,00 | 209.010,00 | 12:50 |
| 12:50 | 11:55 -> 12:20 | DOWN | 209.680,00 | 208.705,00 | 209.192,50 | 13:20 |
| 13:20 | 12:20 -> 12:35 | UP | 209.265,00 | 208.705,00 | 208.985,00 | 14:10 |
| 14:10 | 12:35 -> 13:05 | DOWN | 209.265,00 | 208.280,00 | 208.772,50 | 14:20 |
| 14:20 | 13:05 -> 13:55 | UP | 208.965,00 | 208.280,00 | 208.622,50 | 14:30 |
| 14:30 | 13:55 -> 14:05 | DOWN | 208.965,00 | 208.215,00 | 208.590,00 | 14:40 |
| 14:40 | 14:05 -> 14:15 | UP | 208.730,00 | 208.215,00 | 208.472,50 | 14:55 |
| 14:55 | 14:15 -> 14:25 | DOWN | 208.730,00 | 208.375,00 | 208.552,50 | 15:15 |
| 15:15 | 14:25 -> 14:40 | UP | 209.055,00 | 208.375,00 | 208.715,00 | 16:00 |
| 16:00 | 14:40 -> 15:00 | DOWN | 209.055,00 | 208.520,00 | 208.787,50 | 16:20 |
| 16:20 | 15:00 -> 15:45 | UP | 209.605,00 | 208.520,00 | 209.062,50 | 16:30 |
| 16:30 | 15:45 -> 16:05 | DOWN | 209.605,00 | 208.990,00 | 209.297,50 | 17:15 |
| 17:15 | 16:05 -> 16:15 | UP | 209.550,00 | 208.990,00 | 209.270,00 | 17:55 |
| 17:55 | 16:15 -> 17:00 | DOWN | 209.550,00 | 209.165,00 | 209.357,50 | Vigente no corte |

Detalhes completos e exportaveis: validation/dow-fifty/waves.csv e waves.json incluem IDs da onda e extremos, precos, formacoes, confirmacoes dos dois extremos, maxima/minima, 50%, reconhecimento, evento de origem, terceiro pivo, referencia substituida e nova referencia. events.csv/events.json contem toda a cronologia de estados, reacoes e justificativas; WINZ26-prefixes.json e WIN_N-prefixes.json conservam cada estado por candle.

## Verificacao

DowFiftyEngineTest valida que o segmento terminal nao basta, o tempo de reconhecimento nao e o tempo de B, C ampliado nao atrasa retrospectivamente a conclusao, e a barra confirmadora nao explica um toque anterior. Ha testes simetricos de retorno vindo de cima/baixo, tolerancias independentes, candidato sem criterio definitivo de rejeicao, falsos rompimentos, substituicao, recusa de micro e conclusao ambigua sem alvo.

Todos os 216 prefixos foram comparados incremental/reconstrucao/Replay, com toda a memoria futura de candles fornecida ao reconstrutor e filtrada por cutoff. Reprocessamento e idempotente. A reconexao com a mesma sessao fechada, inclusive com relogio avancado sem novos candles, reconstroi as mesmas observacoes e referencias. Reset retorna a AGUARDANDO_ONDA. Os testes causais existentes de micro, abertura, 51 fractais de 16/10 e Dow principal foram mantidos e executados.

Frontend: 62 testes passaram, incluindo preco/tempo do desenho, apenas uma referencia, ausencia antes do reconhecimento, posicao logica no fechamento, nenhuma alteracao do autoscale, protecao do contrato e independencia de Fibonacci manual/antiga e consulta historica. TypeScript passou. Java: 20 testes executados aprovados, dois condicionais nao executados (baseline original e teste historico legado dependente de propriedade).

Reproducao: com JDK 21, mvn -pl panic-scanner -am test -Dtest=DowFiftyEngineTest,DowStructureEngineTest,MicroDowEngineTest,MicroDowOpeningTest,MicroDowContinuityTest,MicroDowOctober28ValidationTest -Dsurefire.failIfNoSpecifiedTests=false. Depois python frontend/src/features/threecandles/validation/auditDowFifty.py. Capturas: node frontend/src/features/threecandles/validation/captureDowFifty.mjs, com Vite em 127.0.0.1:5174. Frontend: npx tsc -b e node --experimental-strip-types --test tests/threeCandles*.test.mjs.

O criterio de conclusao e conservador e explicitamente experimental, motivado pela lacuna do contrato principal. A hipotese de rejeicao permanece nao validada. Nao se afirma que o professor aprovou esses criterios ou que trajetorias sejam destinos garantidos.
