# M5: prioridade do pivo confirmado mais recente

Branch: `feat/three-candles-foundation`.

## Politica

`m5DisplayGroups.ts` continua sendo a unica projecao visual. A ordem e crescente por confirmacao, formacao e ID (comparacao lexical); em empate o ultimo prevalece. O novo pivo remove TODAS as linhas atualmente representadas do mesmo simbolo a uma distancia absoluta menor ou igual a tolerancia, inclusive entre topo e fundo. O preco permanece exatamente o do pivo novo. Pivos ocultados nao voltam por proximidade com um terceiro preco. Rompimentos nao isentam linhas da substituicao. A antiga opcao de separar movimentos apos rompimento foi retirada porque contrariava essa regra.

15, 30 (padrao) e 50 pontos continuam disponiveis. Zero desativa a compactacao, inclusive entre precos iguais. A projecao e reconstruida apenas com pivos confirmados ate o cursor, a partir da memoria original do backend; nenhum motor, detector, Fibonacci ou referencia H1 foi alterado.

## Memoria e auditoria

Cada grupo mantem todos os membros originais e o historico de substituicoes: pivo antigo/novo, distancia, confirmacao e motivo `SUBSTITUIDO_POR_NIVEL_M5_MAIS_RECENTE`. O painel Auditoria e diagnosticos > regioes M5 permite consultar os ocultados e as decisoes.

O navegador arquiva essa mesma projecao em `POST /api/three-candles/m5-visual-audit`. `M5VisualAuditStore` preserva as decisoes em memoria no backend e valida tipo, identificador, preco, distancia inclusiva, desempate e causalidade. Nao executa agrupamento. A chave separa sessao/simbolo/tolerancia; requisicoes repetidas sao idempotentes. `GET` no mesmo caminho aceita `scope`, `symbol`, `tolerance` e `availableAtMsc`; um cursor anterior nao revela substituicoes ou rompimentos futuros. Como os replays existentes, a memoria nao e persistencia em disco e e perdida no reinicio do backend. Nao ha descarte automatico de escopos arquivados durante a execucao. Uma reconstrucao reenvia os eventos. Falha no arquivamento aparece na auditoria, sem interromper candles ou calculos.

## Fonte e comparacao visual

Referencia utilizada: WINZ26, 28/10/2026, candles M5 nativos exportados do MT5, horario civil sem deslocamento. Fonte congelada em `frontend/src/features/threecandles/validation/oct28/source.json`; 103 candles fechados no snapshot ate 17h35. A imagem fornecida pelo usuario nao contem data/contrato suficientes para confirmar que e esse pregao; confirmacao solicitada, ainda pendente. Nao afirmamos reproduzir o pregao exato da imagem.

27 pivos M5 originais. Politica anterior: 23 linhas. Nova politica: 22 linhas. Uma linha adicional eliminada em relacao a politica anterior; cinco linhas ocultadas em relacao aos 27 pivos originais. Os 27 registros permanecem intactos.

Capturas `before.png` e `after.png`: pagina React e grafico reais, com API interceptada para o MESMO snapshot congelado. Antes usa somente o modulo de agrupamento original; depois usa a implementacao nova. O snapshot nao inclui preparacao H1, portanto a tela informa essa ausencia. Nao e captura de mercado ao vivo. `report.json` contem os representantes e eventos completos.

## Validacao

- 29/30/31 pontos; topo/topo, fundo/fundo, topo/fundo e fundo/topo.
- Varias linhas substituidas pelo mesmo pivo; cadeia de substituicoes sem ressuscitar precos antigos.
- Empates, recebimento fora de ordem, H1 isolado, modo sem agrupamento e rompimentos preservados.
- Prefixos incrementais identicos a reconstrucoes com tolerancias 0/15/30/50, tambem no historico real.
- 70 testes frontend passaram, incluindo Dow e Fibonacci manual/antiga; TypeScript passou.
- Java: auditoria, adaptadores, Dow principal, Dow micro e Dow 50 passaram; um teste de Dow principal previamente marcado como ignorado.
- Hashes dos motores Dow e dos arquivos Fibonacci registrados em `protected-hashes.json` e verificados pelo script de auditoria.

Reproduzir: `node --experimental-strip-types frontend/scripts/auditM5Latest.mjs`. Capturas: `node frontend/scripts/captureM5Latest.mjs <session.json> <saida.png> before|after` com Vite em localhost:5173 e Edge instalado.
