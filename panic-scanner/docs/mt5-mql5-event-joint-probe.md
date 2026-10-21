# WINZ26 — EA experimental DOM + ticks por eventos

Arquivos: `scripts/mql5/WinZ26EventJointProbe.mq5`, auditor offline
`scripts/mt5_mql5_event_audit.py` e testes `test_mt5_mql5_event_audit.py`.
Não altera Java, streams, configurações de produção, estratégias ou sinais.
Não contém OrderSend, CTrade, alterações de conta, horário ou seleção de símbolos.
O EA só aceita um gráfico WINZ26 no terminal Clear e recusa Strategy Tester.

## Compilação e captura manual de 30 segundos

1. No MT5 Clear, use um gráfico separado de WINZ26, sem EA operacional. Não substitua
   um EA existente. Confirme WINZ26 disponível e dados atualizando durante o pregão.
2. Arquivo > Abrir Pasta de Dados; copie o `.mq5` para MQL5/Experts (não Scripts).
   Abra no MetaEditor e pressione F7. O fonte foi compilado aqui com MetaEditor64:
   **0 erros e 0 avisos**; log `scripts/mql5/WinZ26EventJointProbe.compile.log`.
   A compilação gerou `.ex5` ao lado do fonte, sem executar terminal ou EA.
3. No Navegador > Expert Advisors, arraste WinZ26EventJointProbe para esse gráfico.
   DurationSeconds=30 e TimerMilliseconds=20. Não precisa habilitar envio de ordens;
   OnTick continua sendo notificado com negociação automática desabilitada.
4. O EA remove a si próprio ao chegar ao prazo, liberando a assinatura que adquiriu.
   O log Experts informa o arquivo `MQL5/Files/WINZ26-event-joint-<contador>-<chart>.ndjson`.
   Não sobrescreve arquivo existente. Pode ser removido manualmente, mas isso marca
   EARLY_DEINITIALIZATION e captura incompleta. Fechamento forçado pode impedir resumo.
5. Copie o resultado para `panic-scanner/dom-captures/`, preservando o original.
   Rode o auditor offline abaixo. Não foi feita captura real nesta implementação.

```powershell
python panic-scanner/scripts/mt5_mql5_event_audit.py panic-scanner/dom-captures/SEU-ARQUIVO.ndjson --python panic-scanner/dom-captures/WINZ26-joint-probe-30s.ndjson --output panic-scanner/dom-captures/SEU-ARQUIVO.audit.json
```

Substitua SEU-ARQUIVO pelo nome real. A saída deve ser nova. --python é opcional.
A comparação com a captura anterior permite comparar cadência, contagens e falhas.
Se períodos forem diferentes, o auditor não faz comparação evento a evento,
nem conclui que uma diferença no número de negócios represente cobertura melhor.
Se houver sobreposição local aparente, retorna apenas multiplicidade de payloads
iguais e marca `same_session_established=false`; igualdade não identifica negócios
individuais. Para comparação forte, registrar futuras capturas com janela e terminal
comuns, sem transferir relógios/residuais de sessões antigas.

## Arquitetura de aquisição e custo nos handlers

- OnInit registra terminal/servidor, versão, parâmetros, sessão e cursor-semente
  vindo do último tick válido. Faz uma única MarketBookAdd(WINZ26), configura timer
  e captura snapshot inicial explicitamente identificado. Não normaliza timestamps.
- OnBookEvent filtra o símbolo, chama MarketBookGet imediatamente e guarda o livro
  bruto numa fila de observações. Sem ordenação, serialização, escrita ou Sleep
  nesse handler. Cada notificação observada permanece, mesmo que o snapshot repita.
- OnTick apenas registra um gatilho leve e marca recuperação pendente. Não supõe
  execução nem consulta só o último negócio. Cada gatilho recebido fica registrado.
- OnTimer executa no máximo uma CopyTicks(COPY_TICKS_ALL) por passagem, limite de
  2048 linhas, quando há gatilho/backlog ou após 100ms sem consulta. O fallback
  também recupera ticks quando notificações OnTick foram agrupadas.
- No timer são selecionados os 10 melhores BID (preço decrescente) e ASK (crescente),
  sem agregar níveis duplicados, preencher profundidade ausente ou mudar quantidades.
  Livro bruto completo permanece em raw_levels; seleções têm source_index original.
- Fila limitada a 1024 observações; timer grava até 64 por passagem, com flush a
  cada segundo. Overflow/erro de memória, arquivo ou continuidade encerra com
  diagnóstico incompleto; não descarta silenciosamente nem reinicia cursor.
  Finalização drena somente a fila local limitada, sem nova consulta de ticks.
- Não há Sleep, espera ativa ou ressincronização explícita nos handlers. Isso não
  torna a API não bloqueante: CopyTicks é síncrona e pode esperar até 45s em EA
  durante sincronização. MarketBookGet/arquivos também podem atrasar. O limite
  de 30s é de agendamento, não interrupção forçada de uma chamada em andamento.
  Os timestamps mostram eventual extrapolação; não se certifica tempo real rígido.

Serialização/seleção são deslocadas de OnBookEvent/OnTick para o timer, porém
continuam na mesma fila de execução do EA: escrita/cópia lenta pode atrasar outros
handlers. Os limites reduzem o trabalho, não garantem ausência de backlog.

## Cursor e duplicatas

Cursor `(time_msc, ordinal)` conta todas as linhas, incluindo quotes, em ordem
nativa. Query inclusiva reobtém o milissegundo fronteira e pula apenas o prefixo
posicional já consumido. Negócios idênticos com timestamps iguais continuam sendo
linhas distintas. Nenhum Set/distinct/hash de conteúdo remove dados.

Timestamp regressivo, time != time_msc/1000, prefixo consumido desaparecido,
lote cheio sem avanço ou CopyTicks com erro/resultado parcial invalidam o lote e
encerram. Nenhum cursor inválido é aceito. Linhas candidatas recuperadas no lote
inválido são mantidas como evidência com rows_are_new=false e ok=false; auditor
não as conta como fluxo certificado nem avança cursor.

O método pressupõe prefixo estável. A contagem sozinha não detecta toda substituição
ou reordenação dentro do mesmo milissegundo; não existe identificador de negócio
único. Lote maior que a capacidade num mesmo timestamp pode impossibilitar avanço:
nesse caso há erro explícito, nunca salto para próximo timestamp.

Cursor começa inclusivamente no timestamp do tick inicial. Pode haver ticks de
borda anteriores ao início local. Não há drenagem final da fonte, portanto negócios
finais podem permanecer não recuperados. Nenhuma ausência é preenchida.

## Esquema NDJSON e ordem

- session: session_id, símbolo, servidor, data_path, build, parâmetros e seed.
- book_snapshot: origem OnInit/OnBookEvent, sequência global, raw_levels completos,
  best_bid_levels/best_ask_levels com até 10, preços e volumes originais. O membro
  MqlBookInfo.volume_real é exposto como volume_dbl para compatibilidade de campo
  com os probes Python. Tipos a mercado ficam no bruto, fora do ranking limitado.
  `dom_market_time_msc=null`: não se inventa timestamp de mercado para DOM.
- tick_trigger: notificação OnTick, não negócio. `has_query=false`, campos de
  query zerados significam inexistência de consulta, nunca epoch de mercado.
- tick_batch: início/fim de consulta, cursor antes/depois, returned_rows, ticks novos
  com time/time_msc, bid/ask/last, volume/volume_real, flags, ordinal, classificação
  trade/quote e lado derivado. Flags LAST/VOLUME identificam eventos de negócio;
  BUY/SELL exclusivos dão direção, ambos/nenhum AMBIGUOUS. Bits extras ficam em
  unknown_flag_mask, sem significado atribuído ao 1024. Quantidade mantém unidade.
- timer_handler: duração total do corpo do timer até enqueue, incluindo consulta
  e escrita. Pode conter temporalmente outros registros com sequências anteriores:
  é um span de handler pai, não regressão de eventos de mercado.
- summary: contagens, fila máxima, drops, falhas, assinatura, curso da janela,
  start/end e falta de drenagem final. Sem resumo ou fault indica incompletude.
  Mesmo observed_not_certified_complete não certifica todos os eventos da bolsa.

sequence ordena criação/enfileiramento das observações. handler_start/end_us usam
GetMicrosecondCount relativo à instância do EA; não se comparam diretamente ao
time.monotonic Python ou ao GetTickCount64 de outro programa. Query start/end e
queue_delay separam observação de disponibilidade para escrita. Durações dos
handlers leves terminam imediatamente antes de enqueue, sem incluir retorno final.

TimeLocal/TimeGMT antes/depois e TimeCurrent são datetime brutos em segundos,
sem rótulo UTC imposto ao relógio do servidor. Não fornecem exatidão em ms para
alinhar API Python. TimeCurrent pode refletir outro símbolo do Market Watch;
TimeGMT depende do computador. A janela de consulta não é instante de execução.

## Limitações da API

A documentação informa que BookEvent é enfileirado, mas a notificação não carrega
um snapshot histórico. MarketBookGet lê o estado corrente no momento da chamada:
várias notificações podem resultar em snapshots iguais ou saltar estados que já
mudaram antes do handler. Portanto OnBookEvent não certifica todos os estados DOM.

OnTick pode agrupar atualizações e não é um callback de cada negócio. CopyTicks
recupera o histórico disponível no terminal; atraso/cache/sincronização e ordinais
não provam integridade total da fonte. A ordem dos handlers é de observação no EA,
não uma sequência conjunta oficial de eventos DOM e negócios da bolsa.

Referências oficiais:
- https://www.mql5.com/en/docs/event_handlers/onbookevent
- https://www.mql5.com/en/docs/event_handlers/ontick
- https://www.mql5.com/en/docs/series/copyticks
- https://www.mql5.com/en/docs/constants/structures/mqlbookinfo

## Auditoria e testes possíveis sem coleta

```powershell
python -m unittest discover -s panic-scanner/scripts -p "test_mt5_mql5_event_audit.py" -v
```

7 testes sintéticos aprovados: seleção top10 sobre livro completo, duplicatas,
ordinais repetidos indevidos, durações/cadência, snapshots vazios/falhos/lacunas,
resumo ausente, overflow/sequência faltante, lote inválido, span pai do timer,
isolamento de sessões e integridade SHA-256. Não executam EA ou MT5.

Auditor calcula taxas/intervalos observados, duração das consultas/handlers e
espera na fila, multiplicidades, erros e lacunas >500ms como diagnóstico. Não
aplica correção de fuso nem interpreta proximidade como causalidade.

Validação nativa realizada: compilação real do EA com 0 erros e 0 avisos. Isso
valida sintaxe/tipos, não comportamento runtime, carga real ou disponibilidade DOM.
O log de compilação acompanha o fonte. Nenhum resultado de captura foi fabricado.


## Defeito confirmado: duracoes acima de 30 segundos rejeitadas no OnInit

A versao anterior tinha a condicao `DurationSeconds>30` na validacao inicial.
Com 31 ou 300, OnInit retornava INIT_PARAMETERS_INCORRECT antes de gerar filename,
FileOpen, assinatura ou timer. Por isso nao havia NDJSON correspondente: nao era
uma captura iniciada e interrompida no segundo 30, nem erro de gravacao demonstrado.
OnTimer ja usava DurationSeconds, sem teto proprio de 30 segundos.

OnDeinit usava EARLY_DEINITIALIZATION sempre que completed_window=false e fault
estivesse vazio. Isso abrangia parametros recusados, falhas de inicializacao sem
fault e interrupcoes apos inicio; a mensagem isolada nao discriminava essas causas.
Pela API MQL5, OnInit nao-zero gera REASON_INITFAILED=8. O motivo real recebido
agora e sempre impresso numericamente e pelo nome, sem inferi-lo da mensagem.

Correcao limitada: removido somente o teto indevido de 30; DurationSeconds deve
continuar positivo e o padrao permanece 30. Nao houve mudanca de captura, cursor,
selecao do livro, polling, filas ou termino por DurationSeconds.

Diagnosticos ligados a esse defeito:
- OnInit registra causa, codigo de retorno, GetLastError quando aplicavel,
  parametros e caminho de saida. Recusa de contexto, tick inicial invalido,
  arquivo existente, FileOpen, cabecalho, assinatura e timer ficam discriminados.
- FileOpen captura seu erro imediatamente; WriteLine registra codigo de erro,
  retorno da escrita e caminho no log Experts.
- O resumo, quando o arquivo existe, inclui initialized, init_result, init_error,
  file_open_error, write_error e deinit_reason_name, alem do motivo numerico.
- Sem arquivo, a evidencia continua no Experts: output_created=false e caminho
  nao gerado/abertura recusada. Nao e criado arquivo substituto em falha inicial.
- EARLY_DEINITIALIZATION fica reservado a uma janela iniciada e nao concluida,
  sem outro fault; motivo real pode ser remocao, parametros, recompilacao etc.

Arquivo permanece `WINZ26-event-joint-<GetTickCount64>-<ChartID>.ndjson` na pasta
MQL5/Files da instancia de terminal, com recusa de sobrescrita. O caminho absoluto
aparece nos logs de inicio e fim.

### Validacao manual de 60 segundos

1. Copie o fonte atualizado para MQL5/Experts da instancia correta e compile F7.
   Garanta que o EA anexado e esse binario atualizado, nao um .ex5 antigo.
2. Em grafico separado WINZ26 no terminal Clear, configure DurationSeconds=60 e
   TimerMilliseconds=20. Nao altere parametros, periodo ou conta durante o teste.
3. Confirme a mensagem de inicio e o caminho do arquivo. O EA deve ficar ativo
   aproximadamente 60 segundos, sujeito a chamadas sincronas que podem atrasar.
4. Ao concluir, confira deinit_reason=0 (REASON_PROGRAM), initialized=true,
   init_result=0 e o resumo NDJSON: requested_seconds=60 no cabecalho,
   completed_window=true, fault vazio e release_ok=true. O campo requested_seconds
   pertence ao cabecalho, nao ao resumo. Confira erros/drops independentemente.
5. Se falhar, use a causa de OnInit e reason/erros do Experts; nao interprete
   EARLY_DEINITIALIZATION sozinho. Remocao manual e REASON_REMOVE=1; mudanca de
   parametros REASON_PARAMETERS=5; falha de inicializacao REASON_INITFAILED=8.

Validacao realizada sem coleta: compilacao real MetaEditor, 0 erros e 0 avisos;
7 testes sinteticos do auditor aprovados. Hashes das funcoes de aquisicao e ranking
foram comparados antes/depois e permaneceram iguais. A captura de 60 segundos
nao foi executada automaticamente.

## Auditor de qualidade ampliado (versão 2)

O auditor agora gera JSON e Markdown, com P99, verificação entre cursores de lotes,
fila observável, cauda final e classificação por trechos. Veja
[mt5-mql5-capture-quality.md](mt5-mql5-capture-quality.md) para o comando da captura
de 15 minutos, critérios e limitações. A ampliação modifica apenas auditor/testes/
documentação, sem alterações no EA.
