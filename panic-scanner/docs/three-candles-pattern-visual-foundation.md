# Camada visual Alvo → Nega → Testa: registro da etapa de fundação

Atualização: o reconhecimento experimental foi implementado posteriormente com as hipóteses solicitadas pelo usuário. Ver [detector experimental e validação](three-candles-experimental-detector.md). O texto abaixo registra o escopo da entrega visual original; a pendência de reconhecimento descrita aqui é histórica.

## Limite desta entrega

A camada de apresentação e auditoria foi preparada, mas **o reconhecimento automático de padrões reais continua pendente das regras objetivas do usuário**. O repositório não contém essas regras: `ThreeCandlePatternDetector` é uma interface sem reconhecimento, e os documentos de fundação e auditoria registram explicitamente Alvo → Nega → Testa como trabalho futuro.

Não foi presumido que uma troca de cor, um rompimento, um toque nos 50% ou três candles consecutivos constituam o padrão. Não foram encontrados horários de candidatos de compra/venda previamente validados nesta conversa ou no material do projeto. A pergunta sobre critérios e exemplos foi enviada ao usuário.

Faltam definir: condição do Candle 1/Alvo; o que confirma a negação; qual referência o Candle 3 testa e o que valida a retomada; simetria compra/venda; exigência de fechamento ou reconhecimento intrabar; necessidade de candles consecutivos e regras de invalidação. Essas definições devem vir do material do padrão, não do renderer.

## O que está implementado

- `ThreeCandleVisualAudit`: registro independente de evidências fornecidas por um futuro reconhecedor. Não lê OHLC para classificar candles, não usa Dow/Fibonacci e não emite ordens ou resultados financeiros.
- Uma etapa contém número, papel, observação M5 reutilizando `CandleUpdate`, preço de referência, instante de reconhecimento e motivo. Os candles precisam ser distintos e cronológicos; etapas não podem ser puladas ou alteradas depois. Duplicação idêntica é idempotente.
- `snapshot(cutoff)` revela somente etapas conhecidas. Os estados são `CANDLE_1`, `CANDLES_1_2` e `COMPLETE`. Uma classificação tardia pode apontar para um candle anterior, mas jamais aparece antes de `availableAtMsc`.
- Live e Replay enviam `patternAudit` com `PENDING_DEFINITION`, sem padrões. O frontend informa essa pendência e não inventa marcações. M15 não recebe essa camada.
- `ThreeCandlePatternPrimitive`: contorno externo do corpo sem recolorir o candle; Candle 1 azul escuro `#1E40AF`, Candle 2 azul claro `#7DD3FC`, Candle 3 laranja `#FB923C`. Números em pequenos marcadores fora dos pavios, abaixo para compra e acima para venda. Colisões entre números são separadas verticalmente. Pan/zoom usam coordenadas nativas; o overlay não altera autoscale.
- Três checkboxes, inicialmente ligados, controlam a camada, os números e os contornos independentemente. As preferências seguem o mecanismo existente de remount da página.
- Auditoria recolhida contém direção, estado, horário e referência de cada candle, reconhecimento e motivo de cada etapa.

Dow principal/micro, seus 50%, H1/M5, `OpeningStructureContext`, Fibonacci antiga/manual e estratégias permanecem intactos. Nenhum filtro por fluxo, agressão, DOM, stop, alvo, score ou integração operacional foi acrescentado.

## Demonstrações e testes: não são padrões encontrados

Os arquivos em `frontend/src/features/threecandles/validation/pattern-visual/` são **demonstrações de renderização com etapas atribuídas manualmente em fixtures sobre candles nativos**. Não provam reconhecimento e não devem ser usados como candidatos de compra/venda validados. O critério fictício é identificado como `VISUAL_FIXTURE_ONLY_NOT_RECOGNITION`.

Para demonstrar os três estados, as fixtures reutilizam estas observações de WINZ26 em 30/10/2026, no eixo civil MT5:

| Etapa atribuída na fixture | Origem do M5 | Disponibilidade da evidência | Referência de demonstração (close) |
|---|---|---|---:|
| 1 | 09:15 | 09:20 | 170.355 |
| 2 | 09:20 | 09:25 | 170.345 |
| 3 | 09:25 | 09:30 | 170.380 |

As mesmas evidências são usadas em demonstrações de compra/venda para verificar exclusivamente a posição dos marcadores. Isso não afirma que os mesmos candles satisfaçam critérios reais nas duas direções.

Testes Java exercitam o registro de evidências, disponibilidade das etapas, proibição de repaint/etapas faltantes, Replay fechado, reconstrução e 90 observações M1 nativas. Testes frontend verificam projeção causal, controles independentes, cores, posição acima/abaixo dos pavios, preservação dos dados OHLC e rejeição de evidência futura no transporte. Esses testes validam a infraestrutura visual; os testes de reconhecimento dependerão das regras ainda não fornecidas.

Resultados: 88 testes frontend de 3 Candles e TypeScript aprovados; 2 testes Java novos do registro e 6 de regressão dos adaptadores aprovados. Os hashes dos motores, detectores, Fibonacci e estratégias protegidos permaneceram iguais.

Capturas de demonstração usam a interface React real e API interceptada pelas fixtures. Não representam uma sessão com detector implementado:

- [Somente Candle 1, demonstração de compra](../../frontend/src/features/threecandles/validation/pattern-visual/demo-buy-1.png)
- [Candles 1+2, demonstração de compra](../../frontend/src/features/threecandles/validation/pattern-visual/demo-buy-2.png)
- [Três etapas, demonstração de compra](../../frontend/src/features/threecandles/validation/pattern-visual/demo-buy-3.png)
- [Três etapas, demonstração de venda](../../frontend/src/features/threecandles/validation/pattern-visual/demo-sell-3.png)

## Continuação necessária

Após receber os critérios e os exemplos de referência, implementar um reconhecedor separado que forneça evidências a esse registro. Reproduzir os candidatos reais, registrar cada transição com o instante em que suas condições foram conhecidas e confrontar Replay incremental, reconstrução e M1. Até lá, o critério de aceitação de **identificação de padrões reais** não está atendido; somente a camada visual/auditoria está preparada.
