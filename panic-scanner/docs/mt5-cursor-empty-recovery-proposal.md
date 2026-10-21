# Recuperação limitada após CopyTicks vazio — proposta para revisão

Status posterior: implementada no EA versão 1.01. Ver
[implementação e validação](mt5-cursor-empty-recovery-implementation.md).
O texto abaixo preserva a proposta original; o requisito posterior de não gravar
candidatos rejeitados prevalece sobre o parágrafo que sugeria conservá-los.

Primeira etapa: testes sintéticos e desenho de implementação. O EA
`scripts/mql5/WinZ26EventJointProbe.mq5` não foi alterado, compilado ou executado.
Nenhuma alteração em Java, configurações, estratégias, sinais ou ordens.
Esta proposta sucede o diagnóstico em `mt5-cursor-prefix-diagnosis.md`.

## Testes executáveis

Arquivo: `scripts/test_mt5_cursor_empty_recovery.py`. Contém um modelo de referência
local ao teste, respostas artificiais e relógio monotônico em microssegundos.
Não importa MT5 nem altera o capturador Python. Os testes especificam a política
proposta; não são testes da implementação MQL5, que ainda não existe.

| Cenário | Resultado esperado |
| --- | --- |
| Vazio isolado, depois prefixo e ticks novos | Congelar cursor; reconfirmar; publicar somente o sufixo novo |
| Vazio, depois somente prefixo | Reconfirmar sem avançar timestamp ou ordinal |
| Sucesso na última tentativa permitida | Aceitar dentro do prazo |
| Vazios até esgotar tentativas | `CURSOR_CONTINUITY_UNVERIFIED_EMPTY`; nenhuma publicação ou retomada posterior |
| Prazo vencido antes da próxima consulta | Encerrar sem outra chamada |
| Consulta iniciada no prazo termina após o limite | Não confirmar nem avançar |
| Primeiro vazio já consome todo o orçamento | Encerrar com zero retentativas |
| Resposta não vazia sem prefixo ou com prefixo menor | `CURSOR_PREFIX_DISAPPEARED`; nenhuma publicação |
| Prefixo com mesma quantidade e conteúdo alterado ou reordenado | `CURSOR_PREFIX_CONTENT_MISMATCH`; nenhuma publicação |
| Negócios idênticos no mesmo ms | Preservar multiplicidade; sobreposição seguinte não republica |
| Erro de API, retorno negativo ou linhas com erro | `COPY_TICKS_FAILED_OR_PARTIAL`; nenhuma recuperação por vazio |
| Ordem decrescente ou time/time_msc inconsistente | `TICK_ORDER_OR_TIME_ERROR`; nenhum commit |
| Lote cheio sem progresso | Preservar `FULL_BATCH_WITHOUT_PROGRESS` |
| Dois episódios separados | Reiniciar contador por episódio; registrar ambos |
| Seed com ordinal zero | Não há prefixo consumido para reconfirmar; manter semântica atual |
| Fim de sessão durante incerteza | Encerrar incompleto com motivo explícito e duração |

Execução a partir da raiz do projeto, sem gerar bytecode:

```powershell
python -B -m unittest discover -s panic-scanner/scripts -p 'test_mt5_cursor_empty_recovery.py' -v
python -B -m unittest discover -s panic-scanner/scripts -p 'test_mt5_cursor_prefix_diagnostic.py' -v
```

## Política proposta

Estados: `VERIFIED`, `WAITING_PREFIX`, `STOPPED`. Somente uma resposta com todas
as validações aprovadas pode atualizar cursor e contadores de ticks aceitos.
`VERIFIED` significa compatibilidade com o prefixo observado, não certificação
de completude do feed.

1. Antes de CopyTicks, guardar timestamp e ordinal atuais na observação. Priorizar
   erro de API/retorno negativo; uma falha de API não vira vazio transitório.
2. Se `count == 0 && error == 0 && cursor_ordinal > 0`, iniciar `WAITING_PREFIX`.
   Congelar timestamp, ordinal e prefixo consumido. Não definir `fault` ainda,
   não incrementar contadores de negócios e não declarar consulta drenada.
   O primeiro vazio inicia o episódio e não conta como retentativa.
3. Proposta inicial de limites, a registrar no cabeçalho: **3 retentativas** e
   **500.000 us**, o que ocorrer primeiro. São escolhas de política, não valores
   demonstrados pelo diagnóstico. Iniciar duração em `query_start_us` da primeira
   consulta vazia, incluindo sua latência. Guardar também a última confirmação
   anterior para delimitar o intervalo de cobertura ainda indeterminado.
4. Fazer no máximo uma CopyTicks por OnTimer, usando exatamente o mesmo símbolo,
   `COPY_TICKS_ALL`, timestamp e tamanho de lote. Sem Sleep, laço interno, reset
   de ordinal, incremento de timestamp ou dependência de uma nova OnTick.
   Enquanto houver incerteza, agendar no próximo timer elegível mesmo que não
   haja `pending_ticks`. OnTick não reinicia contador nem prazo.
5. Verificar prazo antes e depois de cada chamada. Sucesso na terceira tentativa
   pode ser aceito se terminar antes do prazo. Na fronteira `elapsed >= budget`,
   encerrar sem commit. CopyTicks é síncrona: não há garantia de interromper a
   chamada em 500 ms; o limite impede novas chamadas/commit após seu retorno.
6. Resposta não vazia precisa passar ordem, consistência temporal, quantidade
   do prefixo, identidade ordenada do prefixo e proteção de lote cheio sem
   progresso. Só então fechar episódio como `reconfirmed`, publicar sufixo e
   atualizar cursor atomicamente. Prefixo sozinho encerra incerteza sem avanço.
7. Resposta não vazia incompatível encerra imediatamente com motivo específico;
   não insistir até obter uma resposta favorável. Erros de API e validação também
   encerram. Limite de tentativas/prazo sem confirmação encerra com
   **`CURSOR_CONTINUITY_UNVERIFIED_EMPTY`**.
8. Se a janela de captura terminar, ou houver deinit manual durante espera,
   fechar episódio como não confirmado e manter `completed_window=false`.
   Não prolongar a sessão para obter confirmação. Falhas independentes como I/O
   ou fila preservam seu motivo principal; registrar o episódio não confirmado
   separadamente, sem sobrescrever a falha principal.

## Evidência de prefixo e duplicatas

O EA atual só conta posições no timestamp. Isso não distingue prefixo intacto
de substituição de mesmo tamanho. Para permitir a retomada com evidência mais
forte, a implementação proposta mantém uma cópia **ordenada de todas as linhas
consumidas no timestamp atual**. Atualizar essa cópia somente junto ao commit.
Comparar os campos explícitos de MqlTick: `time`, `time_msc`, `bid`, `ask`, `last`,
`volume`, `volume_real`, `flags`; não comparar bytes de estrutura com padding.
Valores não comparáveis não podem fornecer reconfirmação positiva.

Manter os elementos repetidos na cópia, sem set, deduplicação por preço/volume
ou tolerância que faça linhas distintas parecerem iguais. A comparação verifica
as primeiras `cursor_ordinal` ocorrências; as ocorrências posteriores são novas,
mesmo que tenham todos os campos idênticos. Ao mudar timestamp, guardar o grupo
consumido do novo timestamp; ao permanecer nele, guardar o grupo ampliado.
Com lote atual de 2.048, essa memória é limitada pelo lote. Não alterar paginação
nem contornar `FULL_BATCH_WITHOUT_PROGRESS` nesta mudança.

Essa cópia precisa ser mantida desde o primeiro commit, antes do episódio vazio;
não pode ser reconstruída e considerada confiável a partir da própria resposta
que está sendo testada. Sem evidência armazenada, não autorizar retomada.
Comparar conteúdo também não prova identidade de negócio na bolsa: substituições
perfeitamente idênticas e omissões anteriores à observação permanecem invisíveis.

## Auditoria proposta

Em cada `tick_batch`, acrescentar `continuity_state`, `recovery_episode_id`,
`reconfirmation_attempt` (0 no vazio inicial), `prefix_rows_seen`,
`prefix_content_verified`, `uncertainty_start_us`, `uncertainty_duration_us` e
`continuity_reason`. Conservar `returned_rows`, `error`, tempos de consulta e
cursor antes/depois. Durante espera, cursor antes/depois deve ser idêntico,
`rows_are_new=false` e nenhum tick pode ser publicado como aceito.

`ok` não deve ser suficiente para representar espera: usar estado explícito.
Uma resposta vazia pendente não incrementa `query_errors`; contar separadamente
respostas vazias e retentativas. No encerramento por falta de confirmação,
incrementar a falha terminal uma vez. Erros de API conservam contagem própria.

Registrar um evento de fechamento por episódio, com resultado, âncora congelada,
sequências inicial/final, quantidade de retentativas, início/fim/duração da
incerteza e última confirmação anterior. No summary: episódios iniciados,
recuperados e não confirmados; total de tentativas e duração total/máxima.
Incluir episódio aberto ao encerrar. Duração observada não é quantidade de
negócios perdidos; manter essa quantidade desconhecida.

Em falha com resposta não vazia, conservar linhas candidatas na observação com
`rows_are_new=false` e motivo explícito; não contá-las como aceitas nem descartá-las
silenciosamente. Os dados anteriormente aceitos permanecem preservados. Mudanças
de serialização ficam restritas à auditoria necessária para esta recuperação.

## Pontos de integração futuros (não aplicados)

- `RecoverTicks`: estado, evidência de prefixo, validação antes de commit e decisão
  de retentativa/falha. Não transformar espera em falha genérica no bloco final.
- `OnTimer`: elegibilidade da reconfirmação e fechamento de incerteza antes de
  marcar fim normal da janela. Preservar uma chamada por timer.
- `CObservation` / `FlushObservations`: campos de continuidade; distinguir linhas
  aceitas de candidatas inválidas e registrar fechamento do episódio.
- `OnInit` / `OnDeinit`: inicializar evidência e métricas; registrar limites e
  encerrar episódio pendente no summary.

Antes de qualquer uso real, a etapa de implementação deverá validar compilação
MQL5 e integração do agendamento, commit, serialização e deinit. Os testes desta
etapa cobrem o contrato no modelo; não comprovam esses caminhos no EA.
