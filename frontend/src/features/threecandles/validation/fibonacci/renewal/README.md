# Renovação automática — WIN$N, 16/10/2026

Correção na branch `feat/three-candles-foundation`. O motor mantinha explicitamente a direção da primeira dupla; qualquer extremo contrário era registrado como ambiguidade, sem renovar a Fibonacci.

Agora um topo confirmado acima da máxima vigente inicia uma perna de alta a partir do fundo terminal da baixa anterior. Um fundo confirmado abaixo da mínima vigente inicia uma perna de baixa a partir do topo terminal da alta anterior. Na mesma direção, o extremo amplia a perna e mantém a base. Não há limiar de pontos, filtro de tendência ou regra especial para este pregão. Extremos internos e iguais não mudam a amplitude. Origens anteriores ou simultâneas ao extremo terminal geram ambiguidade. O uso de pivôs internos como novas bases exige outra definição do método e não foi acrescentado.

As versões anteriores mantêm cópias das âncoras; a nova versão referencia a anterior. Eventos `DIRECTION_CHANGED` e `ANCHORS_UPDATED` registram a confirmação que provocou cada alteração e aparecem na auditoria da tela. A seleção manual continua independente. Detectores, agrupamento, outras estratégias e `M5ReplaySession.java` não foram alterados: o bloqueio estava no motor Fibonacci do módulo Three Candles, no frontend.

## Fonte e divergência do exemplo

Usado o snapshot Java `../replay.json`, conferindo todos os OHLC fechados com `historical-data/WIN$N/three-candles-m5/2026-10-16.json` (exportação MT5 local). Os horários abaixo seguem o relógio MT5 codificado na série, sem conversão para o fuso do computador.

**Essa fonte não reproduz os preços 209.190 / 208.430 nem um topo C às 10h25 informados na solicitação.** Nenhuma imagem foi anexada a este turno. Os valores abaixo são os efetivamente presentes no cache local; não foram ajustados para coincidir com o exemplo. A reprodução exata da imagem continua pendente de conciliação da fonte/série. A sequência numérica A=209.190, B=208.430, C=209.500 é coberta separadamente por um teste sintético identificado como tal, incluindo sua imagem especular de baixa.

| Evento no Replay local | Origem do pivô | Confirmação / decisão | Máxima | Mínima | 50% |
|---|---|---|---:|---:|---:|
| A confirmado | 09:25 | 09:40 | 209.375 | — | — |
| B confirmado; Fibonacci A → B | 09:30 | 09:45 | 209.375 | 208.675 | 209.025 |
| C: candle com topo superior | 09:45 | ainda não confirmado | — | — | — |
| C confirmado; atualização B → C | 09:45 | 10:00 | 209.435 | 208.675 | 209.055 |
| Ampliação de alta | 10:00 | 10:15 | 209.690 | 208.675 | 209.182,5 |
| Novo fundo inferior; mudança para baixa | 10:10 | 10:25 | 209.690 | 208.640 | 209.165 |

“Origem” identifica o candle, não o instante exato do negócio que marcou o extremo. Às 09:55, C já está formado no histórico de candles, mas ainda não está confirmado: A → B permanece. Às 10:00 o motor adota C, sem retroagir essa decisão para 09:45. Às 10:35 a versão de baixa das 10:25 permanece vigente. A convenção visual segue 0% na máxima e 100% na mínima.

## Evidências e reprodução

[Auditoria JSON com todas as versões, eventos e checkpoints](report.json). Foram conferidos 113 prefixos: avanço incremental idêntico à reconstrução, histórico prefixal estável, eventos idênticos e uso somente de pivôs disponíveis. Os testes incluem extensão, reversão de alta/baixa, extremos internos/iguais, origem atrasada/simultânea, não duplicação e independência manual. A suíte de frontend passou em 140 testes.

Capturas do frontend real com a API interceptada servindo prefixos causais do snapshot Java: [antes, 09:55](0955.png), [após confirmação, 10:00](1000.png), [após 10:30](1035.png). Não representam uma nova execução conectada ao backend ou ao MT5. Os arquivos `*-browser.json` registram o texto renderizado e erros de navegador.

```powershell
node --experimental-strip-types frontend/src/features/threecandles/validation/auditFibonacciRenewal.mjs
# frontend disponível em localhost:5173; Microsoft Edge instalado
node frontend/src/features/threecandles/validation/captureFibonacciRenewal.mjs
```

## Âncora provisória

Não implementada nesta correção. É possível manter um extremo corrente usando apenas os preços observados, em estado separado e visualmente marcado como provisório, sem criar um pivô confirmado. A fonte de candles fechados usada aqui não demonstra a ordem nem o instante intrabar; validar essa proposta exige prefixos de ticks ou snapshots intrabar, incluindo reset e reconstrução. Máximas/mínimas finais de um candle futuro nunca podem preencher retroativamente esse estado.
