# Estrutura Dow experimental — depuração M5

Implementado na branch `feat/three-candles-foundation`, na página `/three-candles`. Ativar **Mostrar estrutura Dow**; as etiquetas têm checkbox independente. O estado e a auditoria estão disponíveis mesmo com o desenho oculto. O observador é M5 nesta etapa; H1 continua com os níveis e controles existentes.

## Origem da lógica

A investigação não encontrou motor Dow preexistente no repositório. `MarketStructureEngine` acompanha interações com médias móveis, não sequências de topos/fundos. Foi criado `DowStructureEngine`, observador experimental isolado em Java, e integrado ao contrato `dow` das respostas live e Replay do próprio módulo. O frontend consome pontos, movimentos, classificações e eventos do backend; não reconstrói nem classifica Dow.

Nenhum detector, agrupamento, motor de Replay, estratégia operacional ou regra Fibonacci foi modificado. Não há conexão entre eventos Dow e atualização automática/manual da Fibonacci. Respostas de backend sem `dow` continuam utilizáveis e mostram a indisponibilidade, sem inferência no navegador. Reiniciar o backend com esta versão é necessário para disponibilizar o novo campo na API em execução.

## Política explícita para validação humana

Critério versionado: `EXPERIMENTAL_PAIRED_PIVOTS_CLOSE_BREAK_V1`. É uma hipótese de leitura estrutural para depuração, não certificação do método nem sinal operacional.

- Fonte: pivôs M5 originais da sessão, extraídos de `confirmedPivots`, com origem, preço e confirmação do detector existente. Representantes visuais M5 não entram no motor.
- Uma sequência de extremos opostos confirmados compõe os movimentos. Um extremo consecutivo do mesmo tipo só substitui o terminal se for mais extremo; o terminal anterior continua na auditoria e seus eventos permanecem. Pivôs consecutivos que não ampliam o terminal ficam locais. Não existe limiar de amplitude.
- Cada pivô é comparado ao último extremo **estrutural** do mesmo tipo disponível na confirmação. O backend fornece o ID/preço usado e o motivo. HH/HL indicam subida; LH/LL, descida. `FIRST` aparece como `?` e igualdade como `=`; nenhum dos dois confirma tendência.
- ALTA exige HH e HL; BAIXA exige LH e LL. Classes isoladas ou mistas não invertem a tendência vigente. Um pivô contrário pode registrar `POSSIBLE_REVERSAL` mantendo o estado atual.
- Rompimento exige fechamento M5 estritamente além da referência, que já precisava estar confirmada na abertura desse candle. Pavio e igualdade não bastam. O evento conserva o candle do fechamento, preço observado, referência e movimento afetado.
- Em alta, rompimento do fundo protegido por fechamento leva a TRANSIÇÃO; em baixa, rompimento do topo protegido faz o mesmo. O extremo protegido acompanha HL/LH confirmados na direção vigente. Uma tendência oposta precisa do par de classificações correspondente. Um fundo/topo já rompido não pode sustentar a reconfirmação de alta/baixa.
- Pivôs duplos no mesmo candle/mesma confirmação ficam locais, pois sua ordem intrabar é desconhecida. Origens anteriores ou simultâneas ao terminal também ficam locais. Esses casos estão identificados na auditoria.
- Não foi definido critério de lateralização; portanto o motor não emite LATERALIZAÇÃO.

Essas decisões sobre alternância, substituição do terminal, comparação com extremos estruturais e rompimento por fechamento precisam ser confrontadas com a leitura humana antes de qualquer integração operacional. Pivôs locais permanecem visíveis para permitir avaliar justamente esses critérios.

## Desenho e auditoria

Segmentos finos brancos/cinza-claro conectam as origens reais dos movimentos fornecidos pelo Java, usando as APIs nativas de coordenadas de tempo/preço. Pontos cheios: extremos da estrutura vigente. Círculos vazios: locais ou substituídos. Etiquetas: classificações recebidas. Linhas tracejadas e `R↑`/`R↓`: referências rompidas por fechamento. As anotações de rompimento têm visibilidade independente das etiquetas HH/HL/LH/LL, pelo próprio checkbox de estrutura.

Clique em um extremo para abrir preço, origem, confirmação, classificação, comparação e motivo. A tabela permite selecionar pivôs fora do viewport e relaciona cada um aos movimentos vigentes. Eventos registram os movimentos anteriores afetados por extensão ou rompimento, mesmo quando já substituídos. A seleção Fibonacci tem prioridade enquanto estiver ativa; clicar na estrutura Dow não altera âncoras manuais.

O primitive não contribui para autoscale e os toggles não recriam o gráfico. Zoom, eixo manual, níveis H1 fúcsia e M5 amarelos continuam independentes. Nas capturas, a Fibonacci foi ocultada por seu próprio controle para tornar os segmentos legíveis; seu texto/estado permaneceu idêntico antes e depois dos toggles Dow.

## Causalidade

O backend calcula usando somente candles fechados disponíveis e pivôs com `confirmedAtMsc <= cutoff`. Campos de nível como `brokenAtMsc` não são usados para justificar eventos passados: os rompimentos são observados nos fechamentos conhecidos. Eventos da confirmação não podem explicar rompimentos dentro do próprio candle que os confirmou. O marcador pode retornar ao candle de origem somente depois da confirmação.

Foram validados **113 prefixos de WIN$N e 113 de WINZ26**, comparando o mesmo observador incremental à reconstrução integral. Os checkpoints também foram reconstruídos fornecendo deliberadamente toda a memória futura: os resultados permaneceram iguais. Os testes verificam ordem cronológica/alternância dos movimentos, confirmação disponível, eventos disponíveis, idempotência, reset, separação dos símbolos, correções de alta/baixa, pavios, extremos duplos e referência rompida impedindo reconfirmação indevida.

## Fontes e referência da imagem

Pregão usado: **16/10/2026**, recuperado do contexto anterior. A imagem mencionada não foi anexada a este turno; não foi possível certificar sua data/contrato. Não se afirma que estas capturas reproduzem exatamente a imagem ou seus preços 209.190/208.430.

WIN$N usa o cache local `historical-data/WIN$N/three-candles-m5/2026-10-16.json`, identificado como série contínua não mapeada. WINZ26 foi consultado explicitamente no MT5, com `mt5_reader.read_candles(symbol='WINZ26', timeframe='M5', from_date=2026-09-18, to_date=2026-10-16)`, sem converter WIN$N: [exportação separada](WINZ26-m5.json). Os OHLC do dia coincidem nas duas fontes disponíveis; suas identidades e instâncias do observador permanecem separadas.

Horários: relógio civil do MT5 codificado na série, sem conversão automática para Brasília. [Relatório Java completo](report.json).

| Cenário | Cursor | Estado | WIN$N | WINZ26 |
|---|---|---|---|---|
| HH/HL confirmando alta | 11:05 | ALTA | [captura](WIN_N-up.png) | [captura](WINZ26-up.png) |
| LH/LL confirmando baixa | 12:10 | BAIXA | [captura](WIN_N-down.png) | [captura](WINZ26-down.png) |
| Correção sem inverter tendência | 11:15 | ALTA | [captura](WIN_N-correction.png) | [captura](WINZ26-correction.png) |
| Rompimento de resistência | 10:25 | INDEFINIDO | [captura](WIN_N-resistance.png) | [captura](WINZ26-resistance.png) |
| Possível reversão após suporte rompido | 11:35 | TRANSIÇÃO | [captura](WIN_N-reversal.png) | [captura](WINZ26-reversal.png) |

Às 10:25 existe rompimento de resistência, mas isso isoladamente não confirma alta. Cada captura possui um arquivo `*-browser.json` com estado renderizado, detalhes do pivô selecionado por clique real, ausência de erros e verificação de preservação da Fibonacci. As imagens usam frontend/biblioteca de gráfico reais em Edge headless, com API interceptada servindo snapshots do Replay Java sobre dados MT5. Não certificam uma sessão conectada ao backend reiniciado ou ao fluxo ao vivo.

## Reprodução

Na raiz, com Java 21 configurado e os históricos indicados presentes:

```powershell
mvn -pl panic-scanner -am test '-Dtest=DowStructureEngineTest,ThreeCandleAdaptersTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dthreecandles.m5Cache=C:/Users/paulo/project/cripto-analyser/historical-data/WIN$N/three-candles-m5/2026-10-16.json'
# Frontend disponível em localhost:5173 e Microsoft Edge instalado
node frontend/src/features/threecandles/validation/captureDow.mjs
```

O teste real depende explicitamente da propriedade `threecandles.m5Cache`; os testes sintéticos sempre executam. As exportações e checkpoints são evidências da execução, não fontes usadas pela lógica do produto.

Resultado final: **145 testes frontend passaram**, TypeScript e ESLint dos arquivos alterados passaram; **10 testes Java direcionados passaram** (cinco do motor Dow, incluindo Replay real das duas fontes, e cinco dos adaptadores HTTP/live). As dez capturas concluíram sem erros de navegador e com a Fibonacci preservada. A suíte ampliada do módulo também foi executada durante a implementação, incluindo detectores, preparação H1, agrupamento/estrutura e Replay existente.
