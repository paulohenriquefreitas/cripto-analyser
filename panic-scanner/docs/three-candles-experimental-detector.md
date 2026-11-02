# Detector experimental Alvo → Nega → Testa — 30/10/2026

> Atualizacao 31/10/2026: a engine atual esta documentada em [V2](three-candles-pattern-v2.md). Este texto descreve a politica V1 e suas contagens historicas. antes de usar as hipoteses abaixo como especificacao, consultar [aulas e correcoes do usuario](three-candles-rulebook.md). As regras confirmadas pelo usuario corrigem partes desta implementacao experimental.

Implementado na branch `feat/three-candles-foundation`, restrito à página `/three-candles`. Critério `EXPERIMENTAL_ALVO_NEGA_AB_TESTA_M1_V1`, hipótese operacional em validação. Não representa reprodução fiel das aulas. Não envia ordens, não calcula resultado nem alimenta o scanner ou estratégias operacionais.

## Fontes e confronto didático

Foram consultados o caderno local `Caderno_3_Candles_Aulas_01_02_03.md`, as transcrições originais das três aulas e quadros do vídeo original da Aula 02. Manifesto com caminhos, tamanhos e SHA-256: [sources.json](../../frontend/src/features/threecandles/validation/pattern-detector/sources.json). Os vídeos e transcrições privados não foram copiados ao repositório.

| Caso Aula 02 | Evidência conferida | Limite da reprodução |
|---|---|---|
| Venda, 00:51:35–00:54:20 | C11 ALVO nos 50%, C12 NEGA, C13 testa para cima; 136.855 − 20 = **136.835** | Quadro 00:53:25: WINFUT, 02/05/2025, relógio 10:00:04; C13 parcial O136.860 H136.890 L136.855 C136.880. Não há OHLC completo de C11/C12 nem negócios em arquivo. |
| Compra, 01:24:35–01:29:30 | C25 ALVO na mínima, aguarda fechamento C26 NEGA, C27 TESTA; 136.535 + 20 = **136.555** | Quadro 01:28:10: relógio 11:10:06; C27 parcial O136.535 H136.535 L136.510 C136.515. Não há sequência de negócios para reprodução causal exata. |

O teste automatizado didático valida a aritmética dos dois níveis, não inventa três OHLC completos. A decisão mostrada aos 4/6 segundos de C3 não pode ser reproduzida no mesmo instante com M1 fechado. Os quadros extraídos para conferência ficam em `target/pattern-course-review/` (artefatos locais de build). O pregão real abaixo é outro contrato e outra data. Não há alegação de equivalência entre eles.

## Políticas explícitas desta versão

* ALVO: interseção exata entre intervalo OHLC observado e faixa conhecida. Tolerância experimental padrão **0 pontos**, parametrizável; não há regra de 30 pontos atribuída a Leo. Uma faixa pontual fracionária atravessada por OHLC não prova negócio executado exatamente nesse preço.
* A referência precisa estar publicada antes do início do M1 que comprova o contato. Publicação durante esse M1 não prova contato posterior à publicação. Um M1 seguinte pode qualificá-la ainda durante C1. Sem M1, apenas contato conservador do M5 fechado contra referência anterior à sua formação.
* Referências: pivôs M5 conhecidos, H1 da preparação disponível, extremos reconhecidos das ondas micro/principal, 50% micro/principal publicados e extremos da sessão dos **M5 fechados**. Esta última escolha é conservadora: não publica máxima/mínima corrente como suporte anterior ao próprio contato. Histórico rompido é mantido com proveniência, não silenciosamente descartado. Níveis podem atuar na direção inversa conforme a aproximação; não há filtro de polaridade validado pelo curso.
* A direção vem da abertura do intervalo de aproximação em relação à faixa: acima → compra, abaixo → venda. Abertura dentro da faixa é contato rejeitado/inconclusivo. Não usa cor. Candidatos distintos por referência e lado podem compartilhar candles.
* NEGA no fechamento do M5 imediatamente seguinte. **A**: venda H2 ≤ H1; compra L2 ≥ L1. **B**: venda H2 ultrapassa resistência e C2 fecha dentro/abaixo; compra L2 perde suporte e C2 fecha dentro/acima. As duas hipóteses podem coexistir. Registram-se extremos comparados, faixa e variantes; só fechar abaixo de H1 não basta. Ausência do próximo M5 é inconclusiva.
* TESTA apenas no M5 imediatamente seguinte a C2. Hipótese `NEGA_RANGE_RETEST_V1`: retorno à **faixa completa de C2**, conhecida no seu fechamento; não exige metade superior/inferior nem retorno exato ao ALVO original. Venda precisa excursão acima da abertura C3; compra abaixo. Contato sem excursão não comprova tentativa. Essa definição é ampla e deve ser confrontada visualmente.
* Eventos separados: `TESTE_INICIADO`, `REGIAO_TESTADA`, `REJEICAO_OBSERVADA`, `TESTE_INVALIDADO`. A rejeição exige **outro M1 posterior e contíguo** fechando além da mínima do M1 do teste (venda), ou máxima (compra). Cor final não prova sequência. Fechamento M1 além do extremo conjunto C1/C2 invalida, por hipótese. A invalidação permanece no histórico.
* A marca 3 indica teste conhecido, não entrada executada nem rejeição já comprovada. `E` dourado indica somente nível hipotético alcançado após disponibilidade, separado do estado do padrão.
* No primeiro teste conhecido, congela-se o extremo **parcial disponível** de C3: mínima −20 venda, máxima +20 compra. Ordem hipotética pode ficar disponível nesse fechamento M1; não espera reação nem usa extremo final futuro. Validade experimental somente até fechar C3. Não recalcula retroativamente o nível.
* O próprio M1 de posicionamento nunca aciona a ordem. Só outro M1 iniciando após posicionamento, abrindo antes do nível e atravessando-o, comprova alcance. Abertura já além do nível, lacuna ou invalidação no mesmo M1 produz indeterminação; não se inventa fill. Alcance anterior comprovado não é apagado por invalidação/lacuna posterior.
* Sem M1 nativo suficiente, conserva contatos e auditoria, mas teste/sequência ficam indeterminados; não gera `E`. Eventos são publicados no fechamento M1, sem precisão de ticks alegada.

## Isolamento e interface

`ExperimentalThreeCandlePatternDetector` implementa o contrato existente. `ThreeCandlePatternStudy` organiza publicações causais em instâncias próprias dos consumidores estruturais. Não altera nenhum motor. Versões do 50% em formação recebem o horário real de publicação daquele preço; não herdam uma disponibilidade antiga para uma extensão nova.

O replay conserva a fonte completa separada do histórico reduzido de exibição e mantém cache por cursor. Reconstrução inclui apenas observações disponíveis. Live lê M1 nativo uma vez por minuto, sem integrar fluxo/DOM; falha de leitura conserva o conhecido. A consulta M5 pode continuar sem M1, com sequência explicitamente indeterminada.

A camada visual anterior permanece: contornos azul escuro, azul claro e laranja; corpos verde/vermelho originais; números abaixo para compra e acima para venda. Os três checkboxes continuam independentes. O seletor de candidato dentro da auditoria avançada filtra apenas a apresentação, preservando todos os registros. Marcadores idênticos por referência são coalescidos graficamente; a auditoria mantém cada referência. `E` é distinto de `3` e só é desenhado com evidência causal.

## WINZ26 de 30/10/2026

Dados MT5 nativos congelados: **565 M5 de aquecimento**, **86 M5 fechados da sessão**, **430 M1**, até **16:10**. Mesmo contrato WINZ26; preços em pontos. Horários usam a convenção civil do feed já adotada pela página, sem aplicar novo deslocamento de fuso. Fixtures em `src/test/resources/threecandles/pattern-detector/`.

| Direção / candles formados | Região | ALVO conhecido | NEGA conhecida | Teste / ordem disponível | Nível hipotético | Alcance conhecido | Resultado da hipótese |
|---|---|---|---|---|---|---|---|
| Venda 09:15 / 09:20 / 09:25 | M5 histórico 170.415 | 09:18 | 09:25 | 09:26 | 170.280 | 09:27 | Teste e alcance; isso não equivale a rejeição nem operação real |
| Compra 11:10 / 11:15 / 11:20 | Fundo histórico 170.115 | 11:15 | 11:20 | 11:21 | 170.260 | 11:23 | Reação observada 11:23 |
| Compra 11:30 / 11:35 / 11:40 | Fundo histórico 170.115 | 11:34 | 11:40 | 11:41 | 170.590 | 11:43 | Reação observada 11:43 |
| Compra 11:45 / 11:50 | Fundo histórico 170.415 | 11:49 | Rejeitada 11:55 | Sem ordem | — | — | Falha das hipóteses de NEGA |

As compras 11:10 e 11:30 também possuem candidatos separados por contato com 50% **170.165**. O mesmo trio pode gerar horários/níveis diferentes por referência, conforme o primeiro teste causal. Não se deve combinar a ordem de um candidato com o contato de outro. Há mais de uma sequência compatível com a descrição do usuário “perto de fundo anterior e 50%”; não foi atribuída arbitrariamente uma única sequência a essa descrição.

Auditoria integral: [JSON](../../frontend/src/features/threecandles/validation/pattern-detector/WINZ26-2026-10-30-study.json), [tabela CSV](../../frontend/src/features/threecandles/validation/pattern-detector/candidates.csv), [verificação](../../frontend/src/features/threecandles/validation/pattern-detector/verification.json).

* **477 registros candidato/referência**, 140 combinações C1/direção. Não são 477 operações independentes.
* 188 rejeitados na NEGA; 54 testes invalidados; 135 com reação observada; 86 testando; 5 NEGA confirmada sem teste reconhecido; 9 apenas ALVO no corte.
* Gatilhos: 169 alcances hipotéticos, 57 expirados, 23 cancelados, 228 sem posicionamento. Estados do teste e do gatilho são dimensões separadas.
* 22 contatos rejeitados por aproximação indeterminada, com motivo registrado.

**Falsos positivos:** não há rotulagem humana completa para declarar quais candidatos são falsos positivos estatísticos. A venda 09:15 mostra que “teste + E” pode existir sem a rejeição exigida pela hipótese: é um exemplo concreto de por que E não deve virar sinal operacional. A faixa completa de C2 e referências históricas numerosas produzem candidatos fracos; todos permanecem `NAO_VALIDADO`. A compra 11:45 é negativo real rejeitado. Testes sintéticos cobrem o falso NEGA baseado apenas no fechamento abaixo de H1, referência tardia e acionamento no próprio M1 de criação, que são recusados. Não há taxa de acerto/P&L.

## Testes e capturas

Java: **66 casos, 63 executados com sucesso, 3 condicionais ignorados**, incluindo 9 do detector, adaptadores, loader, ledger visual e regressão Dow/Fibonacci/abertura/scanner. O replay compara 430 passos M1 incrementais com reconstruções nos 86 fechamentos e cortes selecionados; também compara replay fechado mantendo a mesma fonte completa e verifica ausência de eventos futuros. Falta de M1 impede alcance hipotético. Os **19 hashes de fontes protegidas** conferem.

Frontend: TypeScript compilado; **181 testes passaram**, incluindo transporte dos snapshots reais, recusa de referência/evento futuro, extremo alterado, execução retroativa, `E` distinto e deduplicação visual. Controles e desenhos anteriores continuam cobertos.

Capturas usam React/canvas reais e snapshots exportados pelo teste Java, com foco de candidato pela própria interface, sem atribuir padrões manualmente:

* Venda: [1 às 09:21](../../frontend/src/features/threecandles/validation/pattern-detector/0921.png), [teste às 09:26](../../frontend/src/features/threecandles/validation/pattern-detector/0926.png), [E às 09:27](../../frontend/src/features/threecandles/validation/pattern-detector/0927.png).
* Compra: [NEGA às 11:20](../../frontend/src/features/threecandles/validation/pattern-detector/1120.png), [teste às 11:21](../../frontend/src/features/threecandles/validation/pattern-detector/1121.png), [E e reação às 11:23](../../frontend/src/features/threecandles/validation/pattern-detector/1123.png).
* [Negativo rejeitado às 11:55](../../frontend/src/features/threecandles/validation/pattern-detector/1155.png). Cada PNG tem um `-browser.json` com ID selecionado, auditoria e erros de console.

Reprodução: `mvn -pl panic-scanner -am test -Dtest=ExperimentalThreeCandlePatternDetectorTest,ThreeCandleVisualAuditTest,ThreeCandleAdaptersTest,ThreeCandleHistoryLoaderTest,*Dow*Test,OpeningStructureContextTest,StructureReactionScannerTest -Dsurefire.failIfNoSpecifiedTests=false`; em `frontend`, `node node_modules/typescript/bin/tsc -b`, `npm test` e, com Vite local, `node scripts/capturePatternDetector.mjs`.

Limite pendente para validação do curso: obter OHLC/negócios completos do pregão didático e rotular candidatos reais com o usuário. A implementação entrega hipóteses auditáveis, não uma reprodução temporal fiel dessas operações de 2025.
