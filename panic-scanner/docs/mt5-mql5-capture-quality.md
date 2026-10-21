# Auditoria de qualidade do EA — relatório versão 2

Auditor: `scripts/mt5_mql5_event_audit.py`. Alterações restritas ao auditor,
aos testes sintéticos e à documentação. EA e componentes operacionais intactos.
O arquivo real de 15 minutos ainda não foi auditado: seus resultados não estão
disponíveis e não foram substituídos por resultados de outras sessões.

## Comando quando a captura estiver disponível

Copie o arquivo já finalizado de MQL5/Files para dom-captures, preservando o original.
Da raiz do repositório, substitua NOME-REAL pelo nome efetivamente gerado:

```powershell
python panic-scanner/scripts/mt5_mql5_event_audit.py panic-scanner/dom-captures/NOME-REAL.ndjson --output panic-scanner/dom-captures/NOME-REAL.quality.json
```

Gera `NOME-REAL.quality.json` e `NOME-REAL.quality.md`. `--markdown` permite indicar
outro caminho para o Markdown. Recusa arquivos de saída existentes, caminhos iguais
entre si e qualquer saída igual a uma entrada. Não altera o NDJSON, confirmado por
SHA-256 antes/depois. Não abre MT5 nem executa coleta.

Opções diagnósticas, sem efeito operacional:
- `--segment-seconds 10`: largura dos trechos relativos ao primeiro handler observado.
- `--gap-ms 500`: marca intervalos de observação maiores que esse valor para revisão.
- `--blocking-ms 100`: destaca consultas síncronas CopyTicks com duração >= esse valor.
- `--python ARQUIVO`: mantém a comparação descritiva opcional anterior; não transforma
  sessões diferentes em simultâneas nem corrige timestamps para forçar sobreposição.

Leitura do NDJSON é linha a linha. Não retém livros completos ou todos os registros;
retém metadados temporais, distribuições e contadores de payload/timestamp necessários
à auditoria. Memória ainda cresce com o número de observações/payloads distintos,
mas não com todos os snapshots de livro deserializados simultaneamente.

## Esquema efetivamente inspecionado

| Registro/campo existente | Uso possível |
| --- | --- |
| session: requested_seconds, cursor_seed_ms, queue_capacity, copy_ticks_batch_limit | Janela nominal, cursor inicial e capacidades declaradas |
| book_snapshot: trigger, raw_levels, top10, ok/error | Eventos observados OnBookEvent, snapshot inicial separado, qualidade e profundidade original |
| query_start/end_us, handler_start/end_us | Intervalos e durações relativas à instância EA |
| queue_delay_us | Demora entre fim do handler e início da serialização; não ocupação da fila |
| tick_batch: cursor_before/after, returned_rows, rows_are_new, ticks | Continuidade de todos os ticks, resultados provisórios e lotes cheios |
| tick: time/time_msc, flags, preços, volumes, cursor_ordinal | Timestamps originais, classificação por flags e multiplicidades |
| summary: peak_queue, dropped_observations, fault, errors, completed_window, start/end_us, no_final_tick_drain | Máximo agregado da fila, falhas e evidência de finalização |

Nenhum campo de ocupação instantânea ou contador de episódios de saturação existe.
Essas métricas ficam null, nunca estimadas a partir de queue_delay_us. Peak_queue
>= queue_capacity informa capacidade atingida, mas não prova descarte por si só.
Dropped_observations reportado é preservado; não é número de negócios perdidos.
O resumo só mantém um fault final, portanto overflow_reported não descarta a
possibilidade de outro problema anterior não localizável.

## Métricas

Contagem de registros DOM, snapshots válidos/vazios/falhos/inválidos, gatilhos
OnTick, consultas de ticks, ticks aceitos e negócios/cotações por LAST/VOLUME.
DOM válido exige sucesso, livro não vazio, validação existente e top10 consistente.
Vazio é observação preservada, porém estado não utilizável na análise conjunta.
Lotes ok=false/rows_are_new=false são evidência provisória e não entram nos ticks
aceitos. Negócio por flags não equivale a evento individual da bolsa certificado.

Distribuições: N, mínimo, mediana, P90, **P99**, máximo e média. P90/P99 por posto
mais próximo (ceil(p*N)); grupo vazio retorna null. Inclui intervalos DOM/CopyTicks,
durações dos handlers/consultas, atraso para escrita e intervalos de mercado.
Ticks repetidos e deltas zero permanecem; uma regressão não é corrigida por sorting.

Chamadas longas são listadas por sequência/janela/duração. Isso mede duração
síncrona observada, não prova rede bloqueada, sincronização MT5 ou latência do feed.
Handlers timer são spans pais que incluem recuperação/escrita: não devem ser
somados aos filhos como tempo independente nem ordenados como eventos da bolsa.

Cursor: before do lote deve corresponder ao after anterior (ou seed, quando
conhecido); novos ticks precisam de timestamp/ordinal crescentes e ordinal contíguo
no mesmo milissegundo; after deve corresponder à última linha aceita. Lote vazio
mantém cursor; lote falho não pode avançar. Campos ausentes tornam essa verificação
indeterminada, não sucesso implícito. O cursor posicional não verifica estabilidade
de todo o prefixo do feed, nem detecta toda perda anterior à API.

Intervalos sem consultas acima de gap-ms são registrados entre observações reais,
sem concluir ausência de mercado ou calcular quantos eventos foram perdidos.
Buracos de sequência, erros de parse e faltas de campos são reportados. Linha final
truncada gera diagnóstico incompleto e não é reescrita. O resumo ausente não é
sintetizado. Todas as métricas se referem somente aos registros legíveis disponíveis.

## Qualidade dos trechos

Trechos fixos a partir do primeiro handler, sem inventar um início de mercado:

- **UTILIZAVEL**: observações conjuntas com DOM válido, cursor verificável e sem
  problema detectado nesse trecho. Serve para auditoria descritiva local; não
  certifica completude da bolsa, sincronização de relógios ou absorção.
- **INCOMPLETO**: evidência positiva de erro, dado inválido, descontinuidade do
  cursor/sequência, consulta falha, interrupção ou falta de finalização.
- **INDETERMINADO**: vazios, intervalos sem observações, chamada longa, campos
  insuficientes, ausência de uma das fontes ou final sem certificado de drenagem.

Um registro falho é localizado pelo handler quando há timestamp. Drops agregados
ou linhas ilegíveis não têm instante conhecido: o relatório não inventa onde
ocorreram e rebaixa a confiança dos demais trechos. Um fault no resumo sem registros
intermediários não comprova que todos os trechos anteriores foram afetados.

Final: mostra última consulta DOM/ticks, fim agendado quando start_us e duração
existirem, e distância da última consulta ao prazo. Valor negativo indica consulta
que ultrapassou o prazo, não latência negativa. end_us inclui limpeza do EA, não
é automaticamente o último instante de mercado capturado.

Sem resumo ou com interrupção/falha/drops: final incompleto. Lote final cheio
sem erro é **indeterminado**, pois não prova backlog. Mesmo completed_window=true,
no_final_tick_drain=true deixa possível cauda de ticks desconhecida. Quantidade
de negócios faltantes fica null. O relatório global pode ser indeterminado mesmo
com muitos trechos locais utilizáveis.

## Referências temporais

time_ranges e time_semantics separam:
- Mercado: time/time_msc originais, sem shift ou conversão de origem presumida.
- Observação: microsegundos relativos à instância EA e valores brutos TimeGMT/
  TimeLocal em segundos, quando presentes.
- Disponibilidade de gravação: início da serialização e queue_delay; não é prova
  de persistência física no disco nem horário de execução na bolsa.

DOM continua sem timestamp de mercado. Não subtrai diretamente microsegundos
relativos do EA de epoch de ticks e não aplica +3h/residual. Os relógios da sessão
ainda exigem auditoria própria antes de qualquer associação causal.

## Validação

```powershell
python -m unittest discover -s panic-scanner/scripts -p "test_mt5_mql5_event_audit.py" -v
```

**18 testes sintéticos aprovados**: 7 anteriores e 11 adicionais. Cobrem percentis,
campos ausentes/null, continuidade entre lotes, ordinais e duplicatas, consultas
longas, fila/drops sem localização inventada, cauda final, truncamento, qualidade
por trecho, geração JSON/Markdown e proteção do original. --help também verificado.
Nenhuma captura real foi executada e nenhum resultado dos 15 minutos foi inventado.
