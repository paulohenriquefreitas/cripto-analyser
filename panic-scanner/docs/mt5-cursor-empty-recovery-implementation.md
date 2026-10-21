# Recuperação de CopyTicks vazio no EA experimental

Implementado em `scripts/mql5/WinZ26EventJointProbe.mq5`, versão 1.01.
Somente o EA experimental, testes e esta documentação foram modificados;
EX5 e log são artefatos da compilação. Nenhum terminal foi iniciado para coleta,
nenhum EA foi anexado a gráfico e nenhuma operação foi executada nesta validação.

## Comportamento implementado

- Vazio com erro zero e ordinal consumido abre `WAITING_PREFIX`. Timestamp,
  ordinal e cópia do prefixo ficam congelados. O vazio inicial não é retentativa.
- Até três chamadas adicionais, uma por ciclo de timer, mesmo sem nova OnTick.
  Orçamento de 500.000 us desde o início da primeira consulta vazia, verificado
  antes da chamada, após retorno e após validação/alocação/preparação do resultado.
  CopyTicks síncrona pode exceder o orçamento; nesse caso não há commit.
- A cópia do prefixo é mantida desde o primeiro commit, numa matriz fixa de 2.048
  linhas. Comparação exata de `time`, `time_msc`, `bid`, `ask`, `last`, `volume`,
  `volume_real`, `flags`, com rejeição de valores não finitos no prefixo.
  Cada posição conta, inclusive cotações e negócios idênticos.
- Ordem temporal, consistência time/time_msc e lote cheio sem progresso continuam
  obrigatórios. Só depois dessas verificações há publicação e avanço do cursor.
  Prefixo sozinho reconfirma sem avançar. Evidência ausente, prefixo menor,
  conteúdo alterado e reordenação encerram explicitamente.
- Sem confirmação dentro dos limites, ou fim de sessão/deinit durante espera:
  `CURSOR_CONTINUITY_UNVERIFIED_EMPTY`, sem marcar a janela como concluída.
  Erros independentes de API, fila ou I/O preservam seus motivos específicos.

O requisito desta etapa de **não gravar candidatos sem reconfirmação** substitui
o trecho da proposta que previa serializar candidatos rejeitados. Uma falha
registra quantidade retornada, erro, motivo e tempos, com `ticks=[]`,
`rows_are_new=false` e cursor inalterado. Nenhum payload rejeitado é escrito.
O descarte é explícito na observação e encerra a captura; não há salto silencioso.

## Auditoria

Cada `tick_batch` inclui estado, motivo, ID do episódio, número da retentativa,
prefixo visto/verificado e início/duração da incerteza. Em espera, `ok=false`,
`rows_are_new=false`, ticks vazios e `query_errors` não aumenta.

Fechamento durante uma consulta aparece no próprio lote como `recovery_result`.
Fechamento por timer/deinit gera `kind=cursor_recovery`. O resultado contém
âncora congelada, primeira/última sequência, tentativas, início/fim/duração e
última confirmação anterior. O summary guarda também o último resultado, inclusive
quando não foi possível alocar a observação de fechamento por falha de fila.
Falhas de I/O continuam impedindo garantia de persistência de qualquer registro.

Campos agregados no summary: `recovery_episodes`, `recovery_reconfirmed`,
`recovery_unverified`, `recovery_attempts`, `recovery_empty_responses`,
`recovery_duration_total_us`, `recovery_duration_max_us`, `last_recovery`.
O cabeçalho registra os limites utilizados. Tempos são do relógio monotônico do EA.

O auditor `mt5_mql5_event_audit.py` não foi alterado: ainda considera `ok=false`
um erro e não reconhece `cursor_recovery`. Seu resultado isolado não interpreta
esta recuperação. Avaliar os novos campos diretamente no NDJSON; não reclassificar
automaticamente uma sessão como completa com base na existência de reconfirmação.

## Validação realizada

```powershell
python -B -m unittest discover -s panic-scanner/scripts -p 'test_mt5_cursor*py' -v
```

32 testes passaram: os 25 anteriores, quatro novos cenários de conteúdo/cópia
do prefixo e três testes estáticos de escopo/arquitetura. Os testes de comportamento
executam o modelo Python, não o código MQL5; os testes estáticos verificam texto
e hashes de funções. A execução nativa de recuperação ainda exige observação em
terminal ou um harness nativo futuro. Não foi alegada execução nativa sintética.

MetaEditor: `C:\Program Files\MetaTrader 5\metaeditor64.exe`, compilação do arquivo
no workspace. Resultado: **0 errors, 0 warnings**. Log em
`scripts/mql5/WinZ26EventJointProbe.compile.log`; EX5 no mesmo diretório.

Foram comparados com a cópia anterior e permanecem idênticos: `BookRow`,
`BestSide`, `TickRow` (inclui classificação/agressão), `CaptureBook`,
`OnBookEvent`, `OnTick`. Demais mudanças no EA restringem-se ao estado/validação
do cursor, agendamento da reconfirmação, metadados e encerramento da recuperação.
Java, configurações, estratégias e módulos operacionais não foram editados.

## Procedimento futuro: captura real de 15 minutos

1. No MetaEditor do terminal Clear usado no experimento, compilar esta versão
   do EA e confirmar novamente zero erros/avisos. Usar o fonte/EX5 correspondente,
   sem substituir outros capturadores. Este procedimento não foi executado aqui.
2. Abrir gráfico `WINZ26`, em período com feed ativo e símbolo disponível, fora
   do Strategy Tester. Anexar apenas o EA experimental com `DurationSeconds=900`
   e `TimerMilliseconds=20`. O EA não envia ordens e não precisa habilitar negociação
   automática para capturar. Não iniciar outros módulos operacionais para este teste.
3. Guardar o caminho NDJSON informado no Experts. Não remover, recompilar, trocar
   parâmetros/gráfico ou fechar terminal antes do encerramento automático.
   Se houver falha explícita antecipada, preservar o arquivo como incompleto;
   não reiniciar sobrescrevendo a evidência.
4. Após encerrar, copiar o arquivo de `MQL5/Files` para análise e preservar o original,
   com hash SHA-256, versão/hash do EA, log de compilação e logs Experts/Journal.
5. Conferir cabeçalho: `requested_seconds=900`, limites 3 / 500000. Conferir summary:
   janela concluída, fault vazio, sem erro de escrita/liberação ou drops. Duração
   deve ser compatível com 900 s mais latências de timer/deinit. Se esses critérios
   falharem, a sessão não passa a validação de captura.
6. Para cada episódio, verificar todas as observações de espera: cursor antes/depois
   igual à âncora, ordinal preservado, ticks vazios, erro de API zero, tentativas
   adicionais entre 0 e 3. OnTick não deve reiniciar episódio nem contador.
   Cada chamada deve pertencer a um ciclo de timer distinto; não há rajada interna.
7. No resultado `reconfirmed`, exigir `prefix_content_verified=true`, duração
   estritamente menor que 500000 us e no máximo três tentativas. Só esse lote pode
   passar a emitir novas posições. No mesmo timestamp, os ordinais novos devem
   continuar após o ordinal congelado, sem eliminar negócios de payload idêntico.
   Conferir contadores raw/trade/quote com o número de ticks aceitos no arquivo.
8. Em resultado não confirmado, exigir parada, `completed_window=false`, ausência
   de commit/payload e motivo explícito. Prazo pode ser observado acima de 500 ms
   pela duração de uma chamada síncrona ou atraso de timer; não pode haver uma nova
   chamada após detectar o vencimento nem aceitar a resposta tardia.
9. Reconciliar resultados por ID: episódios = reconfirmados + não confirmados;
   soma de tentativas, durações e máximo devem coincidir com summary. Diferenciar
   tempo de incerteza desde a consulta vazia do intervalo desde a última confirmação.

Uma captura sem respostas vazias valida operação normal, mas **não exercita
recuperação real**. Registrar essa ausência; não forçar vazio desconectando o feed
nem afirmar completude de negócios da bolsa. Mesmo com prefixo idêntico,
substituições perfeitamente idênticas e omissões anteriores à observação
continuam fora do alcance desta verificação.
