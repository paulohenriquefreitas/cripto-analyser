# 50% do Dow micro e camadas independentes

Branch: `feat/three-candles-foundation`.

## Implementação e isolamento

`MicroDowFiftyEngine` é um consumidor separado do snapshot de `MicroDowEngine`. Usa exclusivamente os movimentos selecionados de `movementHistory` e as decisões `SEGMENT_CREATED`/`TERMINAL_REPLACED`, conferindo os IDs dos dois pivôs, tipos opostos, ordem temporal, direção, preços e confirmações. O nível é `(preço da origem + preço do destino) / 2`, disponível no instante de reconhecimento daquele movimento.

Não consulta candles, máximas/mínimas globais, Dow principal, ajuste, gap ou âncoras de abertura. Não detecta nem reclassifica pivôs. Todas as referências desta versão são `CONFIRMED`; nenhuma extensão não confirmada é calculada. Uma ligação experimental baseada na abertura permanece no desenho de abertura existente e não cria 50% micro.

Cada referência registra ID da onda, IDs dos pivôs de origem/destino, os registros completos desses pivôs com preço/formação/confirmação, direção, máxima, mínima, nível de 50%, disponibilidade e estado. A substituição encerra somente a vigência da referência anterior, preenchendo `supersededAtMsc` e `supersededByWaveId`. Os preços e a onda anterior permanecem preservados. Eventos `REFERENCE_CREATED` e `REFERENCE_REPLACED` mantêm a sequência de trocas. Repetições são idempotentes; cursor regressivo exige reconstrução.

Live e Replay calculam o micro uma vez e acrescentam `microDowFifty` à resposta. A disponibilidade do nível independe de qualquer checkbox. Motores micro/principal, `DowFiftyEngine`, `OpeningStructureContext`, detectores H1/M5, Fibonacci manual/antiga e estratégias não foram alterados. Não há integração com ALVO → NEGA → TESTA. Hashes dos arquivos protegidos estão em `frontend/src/features/threecandles/validation/micro-fifty/protected-hashes.json`.

## Interface e aparência

O dropdown de fractais foi removido. Dow principal, Dow micro, 50% Dow principal e 50% Dow micro são quatro checkboxes independentes, habilitados por padrão. H1 – fúcsia e M5 – amarelo continuam desmarcados por padrão. Abertura experimental conserva seu controle e sua preferência; os demais filtros permanecem na área avançada.

Ocultar uma onda não oculta seu 50%, não interrompe o cálculo e não modifica o snapshot recebido. As preferências sobrevivem aos remounts de sessão pelo mecanismo já existente, sem introduzir persistência em armazenamento. M15 desabilita as quatro camadas M5 sem apagar essas escolhas.

`MicroDowFiftyPrimitive` desenha uma única referência micro vigente: horizontal, ciano `#06B6D4` como o Dow micro, pontilhada `[2,4]`, espessura 1 px, estendida até a borda direita. O rótulo é **DOW MICRO 50% — preço**. O principal mantém o renderer branco de 2,5 px existente, incluindo sua referência em formação quando aplicável.

O início geométrico é o centro da onda, seguindo a apresentação do principal; sua visibilidade só é liberada depois da confirmação. Não há backfill de referências ainda desconhecidas no Replay. Pan/zoom usam coordenadas nativas e nenhuma das referências acrescenta autoscale.

Para níveis próximos/coincidentes, o rótulo micro ocupa outra linha vertical de texto. Perto da borda inferior, ele passa acima dos rótulos. Só o texto muda de posição: o traço permanece exatamente no preço calculado. O histórico é auditável na seção **DOW MICRO 50% — referências e substituições**, sem reativar referências antigas.

## Valores nativos de WINZ26

Horários no eixo civil MT5, sem conversão automática. Todos os valores são pontos do WIN. Formação corresponde ao início do M5 do extremo, não ao instante exato do negócio intrabar.

| Snapshot | Origem micro: preço / formação / confirmação | Destino micro: preço / formação / confirmação | 50% micro | Disponível desde | 50% principal exibido |
|---|---|---|---:|---|---:|
| 29/10 16:00 | 170.985 / 15:25 / 15:35 | 171.550 / 15:35 / 15:45 | 171.267,5 | 15:45 | 171.915, em formação |
| 29/10 16:10 | 170.985 / 15:25 / 15:35 | 172.845 / 15:55 / 16:05 | 171.915 | 16:05 | 171.915, confirmado às 16:10 |
| 30/10 10:15 | 169.070 / 09:45 / 09:55 | 169.785 / 10:00 / 10:10 | 169.427,5 | 10:10 | 169.427,5, confirmado às 10:15 |
| 30/10 10:25 | 169.785 / 10:00 / 10:10 | 169.160 / 10:15 / 10:25 | 169.472,5 | 10:25 | 169.427,5 |

Em 29/10 às 16:00, o principal já acompanha a nova alta observada como formação; o micro ainda usa seu último par confirmado, sem antecipar o topo de 15:55. Em 16:05 o micro reconhece esse topo, substitui sua referência e preserva o par anterior; o principal só confirma às 16:10.

Em 30/10, a abertura experimental conecta antes da primeira onda micro, mas o 50% micro continua ausente às 09:25. O primeiro nível entre pivôs confirmados surge às 09:35: `(170.115 + 170.510) / 2 = 170.312,5`. Às 09:55, a onda micro seguinte produz `(170.510 + 169.070) / 2 = 169.790`. Em 10:25, a nova correção micro muda o seu nível enquanto o principal mantém o seu, demonstrando a independência.

## Validação

- Três testes Java novos: 131 prefixos M5 nativos (113 de 29/10 e 18 de 30/10), consumidor incremental contra reconstrução, inclusive fornecendo histórico micro futuro com corte causal; substituição por extensão do mesmo lado sem apagar onda anterior; abertura sem dois pivôs; idempotência e rejeição de cursor regressivo/outro contrato.
- Replay progressivo nativo: 430 passos M1 de 29/10 até 16:10 e 90 de 30/10 até 10:30, total de 520 observações. O consumidor só muda quando o micro reconhece um movimento com pivôs confirmados. Reconstrução em cada minuto coincide com o Replay incremental.
- Regressões Java selecionadas: Dow principal/micro, teste de 29/10, abertura, adaptadores Live/Replay, Replay progressivo e estratégias. Nenhuma falha; dois casos dependentes de propriedades externas continuam condicionais.
- 84 testes frontend aprovados e `npx tsc -b` aprovado. Cobrem as 16 combinações dos quatro checkboxes, preferências de abertura/remount/M15, preservação da Fibonacci antiga/manual, valores distintos, rejeição de dados futuros/fabricados, linha horizontal, pontilhado/cor/espessura e rótulos coincidentes sem deslocar preços.
- Os quatro snapshots antes/depois preservam integralmente os dados anteriores; o único campo de análise acrescentado é `microDowFifty`. Os hashes dos arquivos protegidos permaneceram iguais.

Executado: `mvn -pl panic-scanner -am test -Dtest=MicroDowFiftyEngineTest,*Dow*Test,OpeningStructureContextTest,ThreeCandleAdaptersTest,ProgressiveM1ReplayTest,StructureReactionScannerTest -Dsurefire.failIfNoSpecifiedTests=false`. Frontend: `node --experimental-strip-types --test tests/threeCandles*.test.mjs` e `npx tsc -b`.

## Capturas

Pares reais da interface React/canvas em `frontend/src/features/threecandles/validation/micro-fifty/`, com API interceptada por snapshots Java sobre fonte nativa MT5. Não são capturas da sessão original do usuário. Os pares preservam cursor, candles, viewport e dados dos motores existentes. Os arquivos `*-browser.json` registram os controles e ausência de erros no navegador.

| Pregão/cursor | Antes: dropdown e um 50% | Depois: checkboxes e dois 50% |
|---|---|---|
| 29/10 16:00, níveis distintos | [antes](../../frontend/src/features/threecandles/validation/micro-fifty/29-1600-before.png) | [depois](../../frontend/src/features/threecandles/validation/micro-fifty/29-1600-after.png) |
| 29/10 16:10, níveis coincidentes | [antes](../../frontend/src/features/threecandles/validation/micro-fifty/29-1610-before.png) | [depois](../../frontend/src/features/threecandles/validation/micro-fifty/29-1610-after.png) |
| 30/10 10:15, níveis coincidentes | [antes](../../frontend/src/features/threecandles/validation/micro-fifty/30-1015-before.png) | [depois](../../frontend/src/features/threecandles/validation/micro-fifty/30-1015-after.png) |
| 30/10 10:25, níveis distintos | [antes](../../frontend/src/features/threecandles/validation/micro-fifty/30-1025-before.png) | [depois](../../frontend/src/features/threecandles/validation/micro-fifty/30-1025-after.png) |

Reprodução visual: `node frontend/scripts/captureMicroFifty.mjs after` com Vite ativo. Os arquivos `before.png` foram capturados antes da troca do dropdown. O backend já em execução não foi reiniciado; precisa carregar esta versão para enviar `microDowFifty`. A interface aceita respostas antigas sem inventar cálculo micro no frontend.
