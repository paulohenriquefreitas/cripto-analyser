# Falha de entradas em 06/10: descarte global de M1 por lacuna local

Data investigada: 06/10/2026. Contrato: WINZ26 exato, conforme cache local e configuracao do backend em execucao. Fonte: MT5, 537 M1 nativos consultados em 01/11/2026; M5 de `historical-data/WINZ26/three-candles-m5/2026-10-06.json`, com 2374 barras incluindo aquecimento. Horarios na convencao nativa do terminal. Ainda nao ha confirmacao dos horarios dos dois exemplos marcados pelo usuario.

## Evidencia em execucao

Habilidade ij-debugger: logpoint sem suspensao em `ThreeCandlesController.create`, linha 98, sessao ThreeCandlesApplication. Captura: `2026-10-06 symbol=WINZ26 M5=2374 M1=0`.

Criacao de Replay fechado retornou `M1_AUSENTE_SEQUENCIA_INDETERMINADA`: 368 candidatos por referencia e zero gatilhos. Criacao progressiva retornou HTTP 422 com `M1 inicial/final ausente em WINZ26 @ 1791290040000`, isto e, 12:34 do terminal. Os dados nativos existem, mas contem lacunas. A primeira lacuna interior e 11:53; a primeira que reprovou o construtor estrito foi 12:34.

`patternMinutes` chamava `progressive` antes de devolver o M1. `ProgressiveM1History.build` exige a primeira e ultima observacao de cada M5 para poder reconstruir o Replay. Uma ausencia posterior no dia abortava a funcao ANTES de salvar/devolver o M1. O controller capturava a excecao e continuava com lista vazia: inclusive os sinais completos das 09h e 10h desapareciam por uma lacuna das 12h34. Nao era prova de ausencia de padrao nem um problema do arquivo application.yml.

## Correcao

O carregador agora consulta, valida identidade/OHLC/ordem/sessao e armazena o M1 nativo independentemente da reconstrucao progressiva. `patternMinutes` entrega a evidencia disponivel ao estudo, que ja trata lacunas localmente. `progressive` continua aplicando a mesma validacao estrita ao seu proprio Replay. Nenhum minuto foi interpolado, nenhum contrato foi substituido, nenhum filtro do padrao ou motor Dow foi afrouxado.

A interface informa `M1 incompleto: trechos com lacunas ficam inconclusivos; os demais continuam sendo analisados.`

## Resultado reproduzido

Mesmos M5, contrato e parametros, agora com o M1 disponivel: 56 registros por referencia com alcance, correspondentes a **11 sequencias distintas de C1 + direcao + preco do gatilho**. Nao sao 56 operacoes; confluencias permanecem como registros separados.

| C1 | Direcao | Gatilho | Alcance hipotetico |
|---|---|---:|---|
| 09:50 | Compra | 212860 | 10:02 |
| 10:15 | Venda | 213235 | 10:29 |
| 11:35 | Venda | 209760 | 11:47 |
| 11:50 | Compra | 210070 | 12:04 |
| 11:55 | Compra | 210165 | 12:07 |
| 14:30 | Compra | 211450 | 14:44 |
| 14:45 | Compra | 211625 | 14:59 |
| 14:55 | Venda | 211490 | 15:07 |
| 15:25 | Compra | 211595 | 15:39 |
| 15:30 | Venda | 211480 | 15:42 |
| 16:00 | Venda | 210780 | 16:13 |

[Verificacao com contexto e referencias](../../frontend/src/features/threecandles/validation/pattern-detector-oct06/verification.json).
[Compra 09:50](../../frontend/src/features/threecandles/validation/pattern-detector-oct06/buy-0950.png).
[Venda 10:15](../../frontend/src/features/threecandles/validation/pattern-detector-oct06/sell-1015.png).

Capturas usam a interface React real com resposta controlada gerada pelo controller/carregador de producao no teste. Os cortes 10:05 e 10:30 limitam a evidencia aos respectivos instantes. Nao sao capturas de uma sessao do usuario reiniciada.

## Regressao

- Carregador: M1 final ausente reprova o Replay progressivo, mas os demais M1 sobrevivem, sao persistidos e reutilizados sem nova consulta.
- Pregao real 06/10: pipeline completo com carregador e controller, fonte MT5 congelada, M1 incompleto declarado, alcances presentes; sem M1 os mesmos dados nao produzem alcances.
- `node frontend/scripts/auditPatternOctober06.mjs` verifica ancora/contexto anteriores a C1, C2 fechado, teste antes do alcance, retorno, offset +/-20 e presenca de cada M1 desde o inicio de C3 ate cada alcance. Nenhum alcance certificado atravessa lacuna nesse trecho.
- Testes existentes do detector em 30/10 e do Replay progressivo continuam incluidos.

Validacao executada: 22 testes Java passaram, 1 teste dependente de propriedade externa ficou ignorado; 106 testes frontend passaram; TypeScript compilou sem erros.

Os numeros medem comportamento da engine, nao acerto financeiro. Os dois exemplos humanos ainda devem ser confrontados pelos horarios exatos. O backend em execucao precisa ser reiniciado e o Replay recriado para carregar o codigo corrigido. A sessao de debug original nao foi interrompida.
