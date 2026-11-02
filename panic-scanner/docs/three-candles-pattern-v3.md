# Padrao de tres candles: contexto Dow V3

Branch `feat/three-candles-foundation`, 01/11/2026. Criterio `DOW_LEG_CLOSE_NEGATION_TEST_RETURN_M1_V3`.

## Contexto

V2 usava o sinal de dois fechamentos M5. V3 usa a pernada em desenvolvimento entre o ultimo pivo confirmado e selecionado do Dow micro e o fechamento M5 imediatamente anterior a C1. Acima da ancora: UP; abaixo: DOWN. Um repique de dois fechamentos nao inverte sozinho uma pernada abaixo do topo ancora. Sem ancora micro utilizavel ou em igualdade, tenta o pivo STRUCTURE do principal. Sem ancora da propria sessao, candle da ancora ou M5 anterior, mantem indefinido. Igualdade final nao autoriza candidato.

Snapshots futuros sao recusados integralmente porque sua selecao poderia ter substituido um pivo. O contexto e congelado antes de C1 e inclui ancora com tipo, preco, formacao e confirmacao. Desempate deterministico: formacao, confirmacao, ID lexicografico. Nenhum motor estrutural foi alterado. Essa e uma definicao objetiva da aproximacao, ainda sujeita a exemplos humanos anotados.

Permanecem as regras V2: C1 reconhecido apenas ao fechar C2 negando a continuacao; C2 nao precisa mudar de cor; C3 precisa testar o fluxo original antes de retornar e alcancar o gatilho. Entrada hipotetica fixa: abertura C3 +/-20. M1 nao prova ordem intraminuto. Sem teste, sem entrada. Stop, tamanho, P&L e ordens reais nao foram implementados.

## Interface

O seletor inicia em Todos os padroes com gatilho observado. Mostra todos os padroes com alcance conhecido e os E, preservando selecao individual, somente gatilhos e todos os candidatos. Os checkboxes de marcacoes, numeros e destaque continuam independentes. A auditoria apresenta a ancora. Confluencias mantem seus registros por referencia, sem conta-los como operacoes independentes.

## Comparacao nativa

WINZ26, 30/10/2026, MT5 congelado em `src/test/resources/threecandles/pattern-detector`: 86 M5 e 430 M1. Horarios nativos do terminal, sem reconversao para Brasilia. Este conjunto nao e o exemplo de 09/10 do historico da conversa.

Mesmos dados e parametros: V2 com 11 sequencias distintas, V3 com 13; 5 adicionadas, 3 removidas, 8 preservadas. Unidade: C1 + direcao + nivel do gatilho, sem afirmar numero de operacoes. V3 tem 241 candidatos por referencia, 122 reconhecidos apos C2 e 50 referencias com alcance, incluindo confluencias.

| C1 | Direcao | Gatilho | Antes | Depois | Motivo |
|---|---|---:|---|---|---|
| 09:10 | Compra | 170380 | Admitido | Descartado | Sem ancora Dow confirmada da sessao |
| 10:25 | Compra | 170395 | Admitido | Descartado | Pernada micro UP |
| 11:00 | Venda | 170375 | Ausente | Admitido | Pernada micro UP |
| 11:10 | Compra | 170210 | Admitido | Admitido | Exemplo preservado |
| 11:25 | Venda | 170480 | Ausente | Admitido | Pernada micro UP |
| 13:20 | Compra | 170475 | Ausente | Admitido | Pernada micro DOWN |
| 13:30 | Compra | 170935 | Ausente | Admitido | Pernada micro DOWN |
| 14:00 | Compra | 170815 | Ausente | Admitido | Pernada micro DOWN |
| 15:30 | Venda | 169690 | Admitido | Descartado | Pernada micro DOWN |

Relatorio integral com contexto e horarios: [comparacao V2/V3](../../frontend/src/features/threecandles/validation/pattern-detector-v3/comparison-v2-v3.json). Exemplos descartados por C2 e expirados sem gatilho tambem estao no relatorio. Sao resultados das regras, sem rotulo independente de qualidade ou lucro.

Capturas da interface React com API interceptada pelos snapshots nativos do teste Java, sem backend/broker real:

- [Compra 11:10 antes](../../frontend/src/features/threecandles/validation/pattern-detector-v3/buy-1110-before.png) e [depois](../../frontend/src/features/threecandles/validation/pattern-detector-v3/buy-1110-after.png).
- [Nova venda 11:00](../../frontend/src/features/threecandles/validation/pattern-detector-v3/sell-1100-after.png).
- [Nova compra 13:20](../../frontend/src/features/threecandles/validation/pattern-detector-v3/buy-1320-after.png).

## Validacao

Teste sintetico: topo 130 e fechamentos 105 ->110 mantem DOWN. Principal UP pode coexistir com aproximacao DOWN; micro tem prioridade. Ausencia de estrutura, snapshot futuro, dia seguinte e lacunas nao inventam direcao. Testes espelhados de compra/venda cobrem negacao, mesma cor, igualdade, ausencia de teste, ambiguidade M1 e offset fixo.

430 observacoes M1 incrementais; comparacao com reconstrucao nos 86 fechamentos M5 e cortes adicionais; replay fechado e progressivo concordam. Frontend compara exatamente candles, estrutura, principal, micro, ambos 50% e abertura nos nove instantes V2/V3 preservados. Rejeita ancora futura, adulterada ou ausente.

`node scripts/auditPatternV3.mjs` regenera a verificacao e a comparacao. `capturePatternV3.mjs` recebe fixture, saida, URL, horario C1 e BUY/SELL. V2 foi preservada para comparacao honesta.

Mais sinais nao comprovam maior acerto. Ainda falta uma base de casos humanos anotados para avaliar qualidade. Reiniciar o backend e recriar o Replay aplica o criterio novo a sessoes em execucao.
