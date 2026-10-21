# Instrumentação de divergência de prefixo — EA 1.02

Implementada no EA experimental do projeto, sem instalação no terminal e sem
execução de coleta. A autorização desta etapa permite preservar a resposta
rejeitada como evidência separada; ela continua proibida no conjunto de ticks aceitos.

- [Diff do EA, auditor e testes](patches/mt5-cursor-mismatch-instrumentation.patch).
- [Manifesto de artefatos e validação](../dom-captures/WINZ26-cursor-diagnostic-validation.json).
- [EA atualizado](../scripts/mql5/WinZ26EventJointProbe.mq5).
- [Novos testes](../scripts/test_mt5_cursor_mismatch_instrumentation.py).

## Alteração implementada

Quando SameTick falha, a validação continua parando no mesmo ponto. São guardados
os índices da primeira divergência. Depois de definir a falha e fechar eventual
episódio de recuperação, uma função copia o prefixo esperado e a resposta bruta
para arrays exclusivos da observação rejeitada. Não avança cursor, não altera
contadores de ticks aceitos, não refaz consultas e não cria uma segunda observação
na fila. Há no máximo um diagnóstico por sessão.

O writer escreve primeiro o `tick_batch` original, ainda com `ok=false`,
`rows_are_new=false`, `ticks=[]` e cursor antes/depois inalterado. Em seguida,
emite uma **linha NDJSON independente**:

```json
{
  "kind": "cursor_prefix_mismatch_diagnostic",
  "diagnostic_schema": 1,
  "parent_sequence": 22,
  "diagnostic_only": true,
  "rows_are_new": false,
  "accepted_rows": 0,
  "reason": "CURSOR_PREFIX_CONTENT_MISMATCH",
  "cursor_before": {"time_msc": 1792430635843, "ordinal": 2},
  "first_response_index": 0,
  "first_prefix_index": 0,
  "different_fields": ["ask", "flags"],
  "returned_rows": 5,
  "expected_prefix_total": 2,
  "truncated": false
}
```

O trecho acima é **um exemplo sintético parcial**, não um diagnóstico retroativo
da captura 8026703. Na linha efetiva também constam:

- `expected_prefix`: prefixo completo do cache, em sua ordem original.
- `returned_raw_rows`: todos os registros devolvidos, até os limites explícitos,
  inclusive os posteriores ao primeiro mismatch e duplicatas idênticas.
- `first_expected` / `first_received`: primeira dupla que não passou na comparação,
  preservada mesmo se houver truncamento dos arrays.
- `query_start_us`, `query_end_us`, `query_duration_us` e `returned_array_size`.
- Contagens gravadas, limites e indicadores de truncamento/alocação.

Os índices são **base zero**, enquanto o ordinal do cursor continua contando
ocorrências a partir de 1. `parent_sequence` vincula a evidência ao lote rejeitado.
O diagnóstico não recebe novo `sequence`: é um anexo do lote, fora da sequência
de observações. Não altera a contagem nem a continuidade dessas observações.

Cada linha dos arrays preserva exclusivamente os oito campos originais MqlTick:
`time`, `time_msc`, `bid`, `ask`, `last`, `volume`, `volume_real`, `flags`.
Não recebe classificação, side, ordinal aceito ou chave `ticks`.
Inteiros são serializados sem conversão para double; floats finitos usam 17
dígitos significativos (`%.17g`), incluindo valores pequenos que seriam arredondados
a zero por um formato decimal fixo. Não finitos são representados por objeto
`non_finite`, mantendo JSON válido. Não se promete preservação do payload binário
interno de NaN; valores não comparáveis são identificados nos campos divergentes.

## Limites e proteção do fluxo

| Limite | Valor |
|---|---:|
| Eventos diagnósticos por sessão | 1 |
| Linhas do prefixo copiadas | Até 2048 |
| Linhas brutas copiadas | Até 2048 |
| Tamanho máximo da linha diagnóstica, sem newline | 1048576 caracteres |

O lote e o cache atuais já têm máximo de 2048, portanto o limite de linhas comporta
integralmente os dois arrays nas condições atuais. A montagem reserva 4096 caracteres
para metadados e primeira dupla e para antes de exceder o orçamento dos arrays.
Um guard final impede emitir linha acima do limite, usando um registro reduzido
válido com a primeira dupla, índices, campos diferentes, contagens e truncamento.
O conteúdo gerado atualmente é ASCII, portanto a contagem também limita bytes
UTF-8 do registro, além do newline.

Indicadores: `truncated`, `allocation_failed`, `row_limit_reached`,
`character_limit_reached`, `expected_prefix_total`, `expected_prefix_written`,
`returned_rows` e `returned_rows_written`. No fallback do guard final, os arrays
ficam vazios e as contagens gravadas são zero. Não cortar strings JSON a meio.
O registro reduzido pode omitir metadados auxiliares; o auditor reconhece o kind
e não depende desses metadados para validade dos ticks.

Se ArrayResize falhar, o diagnóstico registra a limitação e mantém a primeira
dupla sem substituir o motivo original. A cópia usa os arrays próprios, nunca
`o.ticks`. Não há NewObservation extra nem risco adicional de overflow por evento.
A comparação e o orçamento de vazios já terminaram sua decisão antes da cópia.
A serialização ocorre apenas no caminho terminal de falha; a captura normal
não passa a produzir esse volume de dados a cada consulta.

Falha de escrita do diagnóstico mantém o motivo primário de continuidade, mas
os indicadores existentes de I/O continuam ativos. Falta extrema de memória,
disco/terminal indisponível ou erro de escrita podem impedir persistência; não
se promete diagnóstico durável nessas condições. O limite é de recursos/dados,
não um prazo máximo para uma chamada síncrona de escrita.

## Comportamento preservado e auditor

SameTick permanece **idêntico por hash de texto**. Também foram comparadas com a
cópia anterior e permanecem idênticas: `BookRow`, `BestSide`, `TickRow`,
`CaptureBook`, `OnBookEvent`, `OnTick`, `OnTimer`, `OnDeinit`, `ContinuityFields`,
`CloseRecovery` e `StopUnverified`. RecoverTicks recebeu somente os índices e a
chamada diagnóstica no ramo que já falhou; não foram relaxadas validações,
recuperação de vazios ou preservação de duplicatas.

O auditor `mt5_mql5_event_audit.py` precisava reconhecer o novo kind para não
reportá-lo como desconhecido. Agora o guarda em `cursor_prefix_mismatch_diagnostics`
antes de processar sequências, tempos e ticks. Não usa seus arrays como dados de
mercado, mesmo que alguém introduza flags `ok/rows_are_new` ou uma chave `ticks`
inconsistente no diagnóstico. Critérios de validade, classificação de segmentos,
cursor e contadores permanecem os do lote pai. O mismatch continua INCOMPLETO.

## Testes e compilação

```powershell
python -B -m unittest discover -s panic-scanner/scripts -p 'test_mt5_cursor*py' -q
python -B -m unittest discover -s panic-scanner/scripts -p 'test_mt5_mql5_event_audit.py' -q
```

**57 + 18 = 75 testes únicos aprovados**, incluindo 11 novos. Cobrem reordenação,
mudança isolada de bid/ask, flags na segunda posição, duplicatas legítimas, lote
diagnóstico de 2048 linhas, truncamento, valores pequenos/ulong e isolamento no
auditor. Asserções estáticas guardam os limites, o comparador e a ausência de
efeitos em cursor, contadores e fila na função de cópia.

Os testes de comportamento usam o replay Python já existente e fixtures NDJSON
sintéticas integradas ao auditor real. **Não executam a serialização nativa do EA**;
essa parte foi compilada e inspecionada estaticamente. Não se afirmou execução
de recuperação ou geração nativa de diagnóstico no terminal.

Compilador: `C:\Program Files\MetaTrader 5\metaeditor64.exe`.
Fonte compilado: o `.mq5` do workspace, sem copiar para o terminal.

```text
Result: 0 errors, 0 warnings, 7209 ms elapsed, cpu='X64 Regular'
```

Log: `scripts/mql5/WinZ26EventJointProbe.compile.log`.
Fonte 1.02: SHA-256 `1db80823d10629b31207e40fcadef35493cb941b3d3eacecc681669e8fb7e727`.
EX5 do projeto: SHA-256 `c5253b754ba86e2ed56557a7e6b28ef6aec7de35757faf00e4ac24457b817eb5`.

A instalação do terminal continua com fonte `d0a1b607...` e EX5 `10840597...`,
conforme o manifesto. A captura 8026703 continua com hash
`0789ed6419ca212b8b19e5d0be6a25795a95c06c2a152e94e5feb63aa094f46d`.
Não foram alterados Java, estratégias, sinais ou outros módulos operacionais.

## Procedimento de instalação no MT5 — não executado

1. Identificar novamente o terminal Clear por **Arquivo → Abrir Pasta de Dados**.
   O caminho verificado anteriormente é
   `%APPDATA%\MetaQuotes\Terminal\D0E8209F77C8CF37AD8BF550E51FF075`; não presumir
   que outra instância aberta utiliza esse mesmo diretório.
2. Concluir/preservar eventual captura e remover **somente esse EA experimental**
   de todos os gráficos. Compilar/substituir enquanto anexado pode reinicializá-lo.
   Não ativar AlgoTrading nem mexer em outros EAs/indicadores.
3. Fazer novo backup do par instalado `.mq5/.ex5`, fora de MQL5\Experts, com
   hashes/datas/manifesto. Não substituir os backups das atualizações anteriores.
4. Instalar o par 1.02 do projeto em `MQL5\Experts\WinZ26EventJointProbe.*` e
   conferir os dois hashes completos acima. Isso instala exatamente o artefato
   compilado e validado nesta etapa.
5. Se optar por recompilar o fonte já instalado, usar o MetaEditor dessa mesma
   instalação, exigir zero erros/avisos e registrar **novo hash EX5** vinculado
   ao fonte instalado e log. Não presumir igualdade binária com o EX5 do projeto;
   restaurar o par anterior em caso de falha/aviso ou artefato não verificado.
6. Atualizar o Navigator, sem anexar o EA agora. Reanexação só em uma futura
   execução deliberadamente autorizada: confirmar OK executa OnInit e inicia coleta.
   Desabilitar AlgoTrading não transforma esse EA em modo de espera.
7. Numa futura sessão, conferir o contrato do cabeçalho e, **se** ocorrer mismatch,
   o lote rejeitado seguido da linha diagnóstica com parent_sequence correspondente.
   Verificar truncamento e contagens antes de interpretar a resposta. Rodar o
   auditor atualizado, que deve preservar a falha e excluir os payloads rejeitados
   dos negócios/ticks aceitos. Não forçar falha, desconexão ou ordens para esse teste.

A captura antiga não ganha retrospectivamente os cinco registros que faltam.
Esta instrumentação se destina às próximas falhas, quando a versão for instalada
e uma coleta futura for iniciada pelo usuário.
