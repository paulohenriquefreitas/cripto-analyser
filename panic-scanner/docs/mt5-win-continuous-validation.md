# Validação do Times & Trades do WIN$N

Investigação executada em 22/09/2026 com MetaTrader5 Python 5.0.6180. Todas as
consultas usaram janelas controladas de um minuto. Não foram baixados períodos
inteiros, nem consultados anos anteriores à janela de 12 meses.

O resultado bruto reproduzível está em `win-continuous-probe-2026-09-22.json`.

## Profundidade e qualidade

| Distância | Data efetiva UTC | Eventos | Trades | BUY | SELL | BUY+SELL | Sem lado | `volume_real` |
|---:|---|---:|---:|---:|---:|---:|---:|---|
| último minuto | 22/09/2026 12:39:53 | 1.541 | 1.541 | 1.541 | 0 | 0 | 0 | presente |
| 30 dias | 24/08/2026 15:00 | 2.113 | 2.113 | 2.113 | 0 | 0 | 0 | presente |
| 60 dias | 24/07/2026 15:00 | 1.853 | 1.853 | 1.853 | 0 | 0 | 0 | presente |
| 90 dias | 24/06/2026 15:00 | 1.759 | 1.759 | 1.759 | 0 | 0 | 0 | presente |
| 180 dias | 26/03/2026 15:00 | 3.435 | 3.435 | 3.435 | 0 | 0 | 0 | presente |
| 270 dias | 26/12/2025 15:00 | 2.772 | 2.772 | 2.772 | 0 | 0 | 0 | presente |
| 365 dias | 22/09/2025 15:00 | 2.447 | 2.447 | 2.447 | 0 | 0 | 0 | presente |

As sete janelas têm LAST, VOLUME, `volume_real` positivo e inteiro, timestamps
em milissegundos e ordem temporal não decrescente. `volume == volume_real` em
100% dos eventos; não foram encontrados zeros nem frações. Houve vários trades
no mesmo `time_msc` em todas as amostras e eles foram preservados por posição.

Existe Times & Trades em pontos separados por 365 dias. Isso confirma pelo
menos 12 meses recentes para a finalidade desta investigação, mas não afirma
continuidade de cada pregão sem uma auditoria de todos os dias.

## Problema crítico no lado agressor

O `WIN$N` marcou **todos** os negócios de todas as amostras como BUY. A flag foi
sempre `1336` (`LAST|VOLUME|BUY`). Isso também ocorreu quando o contínuo já
representava o WINV26 e cada negócio podia ser comparado com o contrato:

| Janela | WIN$N | WINV26 |
|---|---|---|
| 20/08/2026 | BUY 1.507; SELL 0; ambíguo 0 | BUY 661; SELL 836; ambíguo 10 |
| 10/09/2026 | BUY 3.652; SELL 0; ambíguo 0 | BUY 2.034; SELL 1.350; ambíguo 268 |

Logo, o bit BUY existe no histórico do contínuo, mas **não representa o agressor
real**. Não houve reclassificação por bid/ask. BUY+SELL permanece ambíguo no
contrato específico, conforme a regra definida.

## Comparação WIN$N × WINV26

As métricas usam os trades na ordem retornada. `time_msc` não é chave e trades
repetidos no mesmo milissegundo continuam sendo eventos distintos.

| Janela UTC | Trades WIN$N | Trades WINV26 | `time_msc` | Preço | Volume | Dados de mercado completos¹ | Lado | Evento integral |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 15/07 15:00 | 2.000 | 3 | 0% | 0% | 0% | 0% | 0% | 0% |
| 20/08 15:00 | 1.507 | 1.507 | 100% | 100% | 100% | 100% | 43,861977% | 43,861977% |
| 10/09 15:00 | 3.652 | 3.652 | 100% | 100% | 100% | 100% | 55,695509% | 55,695509% |

¹ Mesmo `time_msc`, `last` e `volume_real`, na mesma posição.

Julho não é falha de fidelidade: o WINV26 tinha apenas três trades nessa janela,
enquanto o contínuo tinha 2.000. O contínuo ainda representava o vencimento
líquido anterior, compatível com WINQ26, mas esse nome não foi afirmado por
comparação direta porque o contrato vencido não ficou acessível de forma segura.

Em agosto e setembro, as sequências de trades coincidem integralmente em
timestamp, preço e volume, inclusive sua ordem. O contínuo omite os eventos de
cotação presentes no `COPY_TICKS_ALL` do contrato e mantém os negócios.

## Rollover e ausência de ajuste

Em 10 e 11/08/2026 o contínuo ainda não corresponde ao WINV26. Em 12 e 13/08,
100% dos trades coincidem em timestamp, preço e volume:

| Data, 15:00 UTC | Trades WIN$N | Trades WINV26 | Dados de mercado completos |
|---|---:|---:|---:|
| 10/08 | 1.323 | 24 | 0% |
| 11/08 | 1.830 | 156 | 0% |
| 12/08 | 1.455 | 1.455 | 100% |
| 13/08 | 4.749 | 4.749 | 100% |

Em 11/08, o contínuo negociava perto de 168.000 e o WINV26 perto de 171.430,
diferença aproximada de 3.430 pontos. Em 12/08, o contínuo passou a copiar
diretamente os preços do WINV26. Isso sustenta empiricamente a descrição “sem
ajustes”: o preço do contrato escolhido é preservado e há salto de nível quando
o subjacente muda. A janela observada não teve ausência nem duplicação de trades;
a troca ocorreu entre os pregões amostrados de 11 e 12/08. O salto precisa ser
tratado conscientemente por futuras métricas de retorno, velocidade, MFE/MAE,
rompimento e VWAP.

## Classificação técnica

| Requisito | Resultado | Ressalva |
|---|---|---|
| Times & Trades disponíveis | SIM | negócios e ordem disponíveis por cerca de 12 meses |
| BUY/SELL disponível | NÃO | campo existe, mas o `WIN$N` classifica 100% como BUY |
| `volume_real` disponível | SIM | igual a `volume`, positivo e inteiro nas amostras |
| milissegundos disponíveis | SIM | duplicatas no mesmo milissegundo são preservadas |
| 6 meses disponíveis | SIM | amostra positiva a 180 dias |
| 12 meses disponíveis | SIM | amostra positiva a 365 dias; não é auditoria diária |

## Conclusão

O `WIN$N` tem cerca de 12 meses recentes de Times & Trades e é fiel ao contrato
subjacente em timestamp, preço, volume e ordem. Também se comporta como série sem
ajuste, incluindo um salto no rollover.

Ele **não está aprovado como fonte única de REPLAY de fluxo** para o futuro
`TradeFlowEngine`, porque o lado agressor histórico está corrompido. Usá-lo
produziria delta/CVD e qualquer confluência BUY/SELL falsos. Pode servir para
backtests de preço e volume, ou como sequência base combinada com uma fonte
confiável de agressor, mas essa combinação não foi implementada nesta etapa.

O acesso ao MT5 permaneceu isolado no probe. Nenhum banco, armazenamento de
ticks, `TradeFlowEngine`, regra ou alerta foi criado.
