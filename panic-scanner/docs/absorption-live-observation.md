# Observação incremental de absorção

## Preparação antes da observação

O modo ao vivo agora prepara a mesma sessão do capturador antes de criar o
Detector. A assinatura DOM e o cursor de ticks permanecem ativos, sem reiniciar
MT5 ou trocar a arquitetura. Replay histórico mantém seu comportamento anterior.

Prontidão exige, simultaneamente:

- terminal conectado em cada verificação;
- pelo menos 500 ms de sequência sem falhas, usando max_poll_gap_ms existente;
- pelo menos três consultas válidas consecutivas de cada tipo desde a última falha;
- DOM não vazio, válido e somente com lados limitados, recebido há no máximo 500 ms;
- ao menos um negócio novo válido recebido há no máximo 500 ms;
- consultas, entregas e intervalos de cada tipo dentro dos 500 ms existentes,
  sequências contíguas, cursor contínuo e lotes drenados.

Os 500 ms de DOM e de integridade vêm de Config, sem alteração de parâmetros.
As três consultas são um requisito adicional de preparação, não um limiar de
classificação. Negócios recentes significam disponibilidade de captura recente,
não atualidade certificada do relógio da bolsa. Mercados sem negócios podem não
atingir prontidão. Falhas reiniciam explicitamente a sequência de preparação,
com motivo registrado; após ativação continuam bloqueando persistentemente.

O registro que comprova prontidão permanece no warm-up. O detector novo recebe
somente o cabeçalho da sessão; suas janelas, livros e negócios começam vazios.
As sequências futuras são numeradas relativamente à fronteira, preservando o
número original em capture_sequence/source_sequence. A guarda verifica o cursor
da fronteira e o intervalo até o primeiro registro; não importa negócios antigos.
Não há classificação ou instância de Detector durante a preparação.

Timeout padrão: 30 segundos, incluindo inicialização. --seconds mede somente a
observação posterior. Uma chamada MT5 bloqueada pode ultrapassar o timeout, que
é verificado quando o controle retorna; não é um cancelamento da API. Sem
prontidão há preparation_summary com diagnóstico/requisitos ausentes, saída 2 e
liberação da assinatura, sem ativação do detector.

```powershell
python -B panic-scanner/scripts/observe_absorption.py --observe-live --warmup-timeout 30 --seconds 10 --output panic-scanner/dom-captures/minha-observacao-preparada.ndjson
```

TimedMT5 envolve somente copy_ticks_from. copy_ticks_api_ms mede o tempo decorrido
dentro dessa chamada; query_python_ms é o tempo total da consulta menos o tempo
da API, incluindo validação, cursor e overhead Python. São tempos decorridos,
não tempo de CPU exclusivo. Nos registros warmup, ambos aparecem dentro de source;
nos registros observation, aparecem diretamente. Cada warmup preserva o registro
bruto, o estado de conexão, DOM vazio/não vazio, motivos e contagens da sequência.

### Teste com preparação — 20/10/2026, 17:48 de Brasília

Artefato: dom-captures/observation-warmup-live-20261020.ndjson.
Warm-up: 0,521106 s; primeiro DOM vazio, seguido de sequência válida de cinco
consultas DOM e 25 consultas ticks. Foram seis consultas DOM no warm-up ao todo.
Após prontidão: 10 s de observação, 99 consultas DOM e 480 consultas de ticks,
nenhum bloqueio, nenhuma transição e nenhum erro de captura/cursor. Ao todo,
incluindo preparação: 64 negócios e seis quotes. Assinatura liberada com sucesso.

| Medida | Média ms | Máximo ms |
| --- | ---: | ---: |
| API copy_ticks_from no warm-up | 0,140804 | 0,225100 |
| Python da consulta no warm-up | 0,138812 | 0,419100 |
| API copy_ticks_from na observação | 0,151522 | 11,607700 |
| Python da consulta na observação | 0,118319 | 1,828500 |
| Detector.feed | 0,085430 | 0,419600 |

O pico de 7,686 s do teste anterior não reapareceu: sua causa interna continua
não comprovada. A sessão sem bloqueios valida a preparação e a observação neste
intervalo curto, não certifica completude do feed nem qualidade preditiva.

Validação: 43 testes no comando test_observe_absorption.py (inclui testes
importados do detector e do probe), além de execução direta dos 24 testes do
detector e nove do probe. Cobrem prontidão, timeout sem detector, limpeza das
janelas, cursor da fronteira, bloqueio persistente e tempos separados API/Python.

Ativação explícita, exclusivamente leitura. Não altera application.properties,
EA, Java, estratégias, gráfico ou Config/Detector. Sem execução de ordens.

O probe mantém sua saída antiga; run(..., on_record=callback) chama o callback
sincronamente antes de serializar ou fazer flush. out=None desliga a saída bruta.
O callback recebe o registro original, lote inteiro, sem ordenar ou deduplicar.
Uma Observation possui exatamente um Detector por execução, sem finish() em EOF.

Da raiz do projeto, com destinos ainda inexistentes:

```powershell
python -B -m unittest discover -s panic-scanner/scripts -p test_observe_absorption.py -q
python -B -m unittest discover -s panic-scanner/scripts -p test_experimental_absorption.py -q
python -B panic-scanner/scripts/observe_absorption.py --replay panic-scanner/dom-captures/WINZ26-joint-probe-30s.ndjson --output panic-scanner/dom-captures/meu-incremental.ndjson
python -B panic-scanner/scripts/observe_absorption.py --observe-live --seconds 10 --output panic-scanner/dom-captures/minha-observacao.ndjson
```

Sem --observe-live não há ativação ao vivo. Replay aceita o schema Python,
entrega linha a linha, não simula latência ao vivo nem inventa transição em EOF.
Saída 2 indica bloqueio ou erro; 0 não certifica completude do feed.

Cada registro gera uma linha observation; transições incluem evidência integral
imutável e estado original. DADOS_INSUFICIENTES é apresentado como INCONCLUSIVA.
Campos temporais, em ms salvo os UTC ISO:

- query_interval_ms: diferença entre inícios de consultas do mesmo tipo.
- query_duration_ms: duração existente da consulta, não latência de mercado.
- delivery_ms: retorno da consulta até entrada no callback, pelo monotônico
  comum ao processo. Inclui preparo do lote e construção do registro; null no replay.
- received_utc: entrada no callback, não recebimento individual do tick.
- receipt_to_feed_ms: entrada no callback até chamada ao detector.
- guard_ms: tempo das verificações externas, incluindo terminal_info ao vivo.
- processing_ms: duração de Detector.feed, separada da guarda e da escrita.

Toda detecção precede gravação/flush do NDJSON. Escrita síncrona pode atrasar
a próxima consulta, visível nos intervalos. Não há limite certificado de entrega.

A guarda externa usa os limites existentes do detector. DOM inválido/vazio,
DOM mais velho que max_dom_age_ms ou entrega acima de max_poll_gap_ms bloqueiam
persistentemente; terminal_info desconectado/indisponível também. O detector
preserva suas checagens de sequência, cursor, gaps e consultas lentas. Retomar
exige outra execução/sessão; nenhuma chegada posterior limpa falhas. Uma chamada
MT5 bloqueada impede novas entregas/classificações; o gap é detectado ao retornar.
Não há detecção instantânea de desconexão enquanto a API está bloqueada.

## Validação real em 20/10/2026

Reprodução incremental da captura conjunta de 15/10: 1.734 registros,
zero transições, bloqueio no registro 3 por DOM inutilizável. A guarda é mais
conservadora que o replay histórico: livro vazio não recupera silenciosamente.
Processamento médio/máximo: 0,067600/2,717100 ms.

Teste Clear em 20/10, 17:20:13–17:20:23 de Brasília: 24 consultas DOM
(23 não vazias, uma vazia), 106 consultas de ticks, 282 negócios e 14 quotes.
Sem erros de cursor/consulta reportados pelo capturador; assinatura liberada.
Bloqueio desde registro 3 por DOM vazio; depois poll_gap e query_latency_gap.
Nenhuma classificação emitida. both_observed significa somente captura conjunta,
não aprovação da integridade pelo detector.

| Medida ao vivo | Média ms | Máximo ms |
| --- | ---: | ---: |
| Retorno da consulta → callback | 0,327925 | 23,999900 |
| Callback → feed | 0,811138 | 3,564300 |
| Verificação externa | 0,788215 | 3,523400 |
| Detector.feed | 0,122240 | 2,457200 |

Maior duração da consulta de ticks: 7.686,155900 ms. Maiores intervalos DOM/ticks:
7.714,758600/7.715,479500 ms. Não foi determinada a causa da chamada lenta.
Os artefatos observation-incremental-20261020.ndjson e
observation-live-20261020.ndjson em dom-captures contêm as medições originais;
não são captura bruta de mercado. Incluem o resumo original, cujos campos agregados
devem ser lidos como resumo, não duração de uma consulta individual.

Esses valores não medem latência de mercado ponta a ponta. Flags com bit 1024
continuam sem semântica certificada, DOM não tem timestamp de origem e ticks
não têm recebimento individual. O teste valida a conexão em memória e o bloqueio;
não valida qualidade preditiva ou uma sessão íntegra com classificações positivas.
