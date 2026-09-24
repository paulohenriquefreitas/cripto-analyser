# Canonical LIVE Shadow — validação controlada

O LIVE atual continua como autoridade para WebSocket, gráfico, SMA9, SMA21, VWAP e MarketStructure. Nenhum estado canônico é publicado nesses caminhos. Trade Stream e Research Replay não são alterados.

## Executar (PowerShell, raiz do repositório)

Requisitos: JDK 21, Maven, Python com MetaTrader5, terminal MT5 conectado e WINV26 disponível. O script aceita somente `30m` ou `60m`; usa 60m por padrão.

Sem backend/gráfico aberto, uma sessão controlada inicia os beans reais do Panic Scanner na porta 8080 e um cliente sem interface que usa o WebSocket OLD e solicita `/api/mt5/candles` na inicialização e nas viradas M5. Ao final fecha somente a aplicação criada por essa sessão:

```powershell
.\panic-scanner\scripts\run-canonical-shadow.ps1 -StartLive -Symbol WINV26 -Duration 60m
```

Não use `-StartLive` se outra aplicação já ocupa 8080. Não há substituição nem encerramento de servidor existente.

Para observar uma aplicação já aberta, inicie-a com `--canonical.shadow.capture-enabled=true`, mantenha o gráfico MT5 aberto (ele inicializa e atualiza o histórico OLD), e execute:

```powershell
.\panic-scanner\scripts\run-canonical-shadow.ps1 -Symbol WINV26 -Duration 60m
```

O modo externo apenas captura observações; não faz consultas de ressincronização no OLD. O endpoint de captura é opt-in, restrito a loopback e protegido por token efêmero. Expira em 30s sem consumo.

O build monta o classpath automaticamente. `-SkipBuild` reutiliza artefatos já preparados, somente se estiverem atualizados. `-Report caminho.json` muda a saída; padrão: `panic-scanner/target/canonical-shadow-report.json`.

## Fonte isolada e cursor

`mt5_canonical_price_stream.py` consulta `copy_ticks_from(..., COPY_TICKS_ALL)` e só emite linhas com bit `TICK_FLAG_LAST`. Não importa o Trade Stream. Usa `time_msc` e preços originais; não ajusta timezone.

A API Python recebe data inicial em segundos. A consulta sobrepõe o segundo do cursor. O cursor lógico `(timeMsc, ordinalWithinTimestamp)` conta **todas** as linhas brutas, inclusive quotes, e pula somente o prefixo já consumido. Eventos de mesmo milissegundo e eventos idênticos são preservados. `rowsFetched` inclui sobreposição; `rowsReceived` conta linhas novas pelo cursor. `sameTimeMscEvents` conta eventos LAST adicionais depois do primeiro de cada grupo. `duplicateEvents` conta pares canônicos consecutivos iguais `(timeMsc, price)`, sem removê-los; não afirma que sejam retransmissões da bolsa.

Batch cheio é drenado imediatamente. Não se libera watermark antes de drenar o intervalo. Falta de avanço, desaparecimento do prefixo, timestamps fora de ordem ou erro MT5 encerram a sessão explicitamente. Se um único segundo exceder o limite de 100.000 linhas e a ponte não permitir avançar, falha com `SOURCE_GAP`, em vez de ignorar o tail. Não há restart automático: exige sessão nova, novo warm-up e novos contadores.

O watermark usa `floor(latestTimeMsc / 1000) * 1000 - 1001`: ret?m o segundo corrente e um segundo completo anterior (margem m?nima de 1001 ms), mesmo na virada exata. A vers?o inicial usava `-1`, que podia liberar um evento com somente 1 ms de margem; um teste de regress?o reproduziu e corrigiu essa falha. Uma linha atrasada anterior a um watermark já liberado invalida a comparação e gera erro. O MT5 não fornece aqui um sequence number global da bolsa: integridade significa ausência de perda/reemissão **detectável nesta fonte**, reforçada pela comparação oficial, não prova absoluta de entrega da bolsa.

Referência da API: https://www.mql5.com/en/docs/python_metatrader5/mt5copyticksfrom_py

## Atividade, warm-up e alinhamento

A sondagem exige LAST novos após o snapshot inicial em pelo menos dois `time_msc` distintos. Snapshot estático e avanço apenas de BID/ASK não bastam. Sem atividade durante a sondagem: `LIVE_VALIDATION_NOT_RUN`, `NO_ACTIVE_MARKET`, `INSUFFICIENT_EVIDENCE`, saída automática. Nenhum replay substitui o teste.

Antes de emitir eventos, busca até 1000 candles oficiais M5 **fechados antes** do bucket inicial; exige pelo menos 20 para disponibilizar SMA21 com o candle corrente. Usa a política do `IntrabarM5Processor`. Reconstrói o candle corrente desde o início do bucket com LAST históricos da mesma fonte, sem semear OHLC de snapshot. Esses eventos são bootstrap, não evidência de duração LIVE.

`comparisonStart = anchor + 1`: só observações posteriores à âncora participam. `SHADOW_COMPARABLE` exige ambos os estados inicializados, símbolo alinhado e watermark suficiente. Reemissão do último tick depois de sincronização REST é marcada e excluída. O primeiro candle reconstruído pode ser comparado ao oficial, mas não conta em `completeSessionBucketsCompared`.

## OLD real e comparação temporal

A captura ocorre dentro do lock do `Ta4jMt5Sma9Adapter.onTick`, depois de aplicar o tick. Copia candle/SMA para uma observação imutável e oferece a uma fila limitada. Não executa IO, callbacks do shadow ou engines no thread LIVE. Overflow incrementa perda diagnóstica; o LIVE segue. O consumidor falha a validação ao detectar essa perda.

O comparador tem um único dono e faz join **as-of**: para OLD no instante `t`, espera o watermark canônico alcançar `t` e usa o último estado canônico com `timeMsc <= t`. Não compara relógios de chegada. Se existem vários LAST exatamente em `t`, o polling não informa o ordinal observado: a amostra é contabilizada como ambígua e excluída, sem inventar alinhamento. O evento canônico continua processado normalmente.

SMA9/SMA21 usam tolerância absoluta `1e-8` ponto apenas para floating point. Price/OHLC usam igualdade exata. Campos ausentes ou séries incompletas são separados das divergências comparáveis. A captura de estados retém aproximadamente dois minutos para entrega HTTP atrasada; atraso além da retenção gera estado ausente. Filas e mapas têm limites e falham explicitamente, sem crescimento ilimitado.

## Fechamentos e evidência

O OLD final é a última observação válida do bucket antes da virada. Não é substituído pela correção REST posterior. O canônico final é o último estado LAST do bucket. Só se compara depois de ambos os feeds ultrapassarem o fechamento e existir candle oficial fechado.

A fonte consulta oficiais por virada, após margem de dois segundos; nunca por evento. Retenta a cada cinco segundos se faltar a barra fechada esperada. SMA oficial é calculada em uma instância independente do adapter TA4J, com a série oficial. Buckets sem LAST não geram candles artificiais. A ausência de candle também na fonte oficial não cria divergência artificial.

O relatório separa OLD×CANONICAL intrabar, OLD×CANONICAL fechado, CANONICAL×OFFICIAL e OLD×OFFICIAL. As evidências incluem até 100 combinações relevantes de fase/bucket/campos, os últimos 16 eventos recebidos e testemunhas dos extremos do bucket. Esses últimos eventos são contexto de aquisição; não se afirma que todos tenham precedido a observação OLD atrasada. A sessão inteira não fica em memória nem é persistida.

Polling pode perder extremos ou fechar com preço antigo. Registrar isso não significa automaticamente bug. O OHLC oficial serve para avaliar qual caminho representou o candle. O shadow nunca imita a perda do OLD.

## Duração, encerramento e decisão

O cronômetro de 30/60 minutos começa na primeira comparação válida, depois do bootstrap. Sem novos LAST por 30s durante a sessão, encerra como incompleta. Há deadlines separados para startup e prontidão; a sessão não depende de Ctrl+C.

Para `SUPPORTS_MIGRATION`: duração configurada concluída, ao menos seis buckets inteiros comparados, seis comparações triplas, amostras OLD cobrindo pelo menos aproximadamente 30 minutos, atividade recente, nenhum fechamento canônico pendente e igualdade canônico/oficial, incluindo SMAs. Sessão suficiente com diferenças oficiais resulta em `DOES_NOT_SUPPORT_MIGRATION`; falta de dados, falha ou duração insuficiente resulta em `INSUFFICIENT_EVIDENCE`. Não há promoção automática.

Métricas incluem linhas e LAST recebidos, grupos iguais, duplicatas preservadas, erros, filas máximas, latência máxima de fila, tempo de processamento e eventos/s. Divergências intrabar não precisam ser zero. A execução usa instâncias privadas de processor; não instancia um segundo MarketStructureEngine nesta etapa, pois estrutura não é o critério.

Fechamento normal: EOF no stdin Python, `mt5.shutdown`, espera limitada e término forçado do filho se necessário. Falha do shadow não fecha o LIVE externo. No modo `-StartLive`, o contexto criado para o experimento é encerrado ao terminar o experimento, inclusive em falha. O servidor e seu lifecycle encerram seus próprios streams.

## Testes

```powershell
python -m unittest discover -s panic-scanner/scripts -p 'test_*.py'
mvn test -B
```

Os testes determinísticos cobrem cursor/duplicatas/full batch/erros/atividade/bootstrap/oficiais, processo filho real sem acesso a MT5 (graceful e forçado), comparador por campo/alinhamento/tolerância/gaps e o extremo 188750 perdido pelo polling. O cenário de mesma sequência usa o adapter OLD real e o processor canônico.

Nota de ambiente: carregar MetaTrader5/NumPy antes de iniciar a thread bloqueante de stdin. Em Windows/Python 3.13 foi observado carregamento nativo bloqueado até stdin EOF na ordem inversa. A ordem foi corrigida apenas na nova fonte e coberta por teste.
