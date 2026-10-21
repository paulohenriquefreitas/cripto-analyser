# Prova simultânea DOM + Times & Trades WINZ26

Script independente e manual, sem integração Java ou histórico definitivo:
`scripts/mt5_dom_trades_probe.py`. Uma inicialização MT5, contrato WINZ26,
uma assinatura DOM e uma thread chamando a API de forma serializada.
Não altera streams operacionais, configurações, estratégias ou sinais.

Com o terminal Clear conectado durante o pregão, execute da raiz do repositório:

```powershell
python panic-scanner/scripts/mt5_dom_trades_probe.py --seconds 30
```

A saída é NDJSON diagnóstico no console. Para guardar um artefato experimental
separado (opcional, exige arquivo ainda inexistente):

```powershell
python panic-scanner/scripts/mt5_dom_trades_probe.py --seconds 30 --output panic-scanner/dom-captures/WINZ26-joint-probe-30s.ndjson
```

Com --output, o resumo também aparece no console. Não sobrescreve, não importa
para histórico canônico e não cria diretórios automaticamente. O Python deve
ter MetaTrader5 instalado. --help não importa nem inicializa MetaTrader5.

Reutiliza initialize() sem parâmetros, seleção e assinatura/liberação do probe
DOM, validação e serialização JSON desse probe; query/Cursor/Counters do capturador
canônico para leitura incremental de TODOS os ticks e identificacao LAST/VOLUME do T&T. A direcao usa o classificador experimental
mt5_experimental_aggressor, sem alterar a funcao dos streams operacionais.
Não chama os entry points operacionais nem seu normalizador fixado em WINV26.

O tick inicial deve ter time_msc positivo. Zero/None encerra com diagnóstico:
não usa hora local como substituta do timestamp de mercado. O cursor inicia
inclusivamente nesse milissegundo, ordinal zero; ticks iniciais podem preceder o
começo local da janela. initial_tick_time_msc permite identificar essa borda.

DOM: alvo 100 ms, preservando livro inteiro, ordem, campos originais, repetições,
vazios, erros e anomalias. Ticks: alvo 20 ms, COPY_TICKS_ALL, cursor de timestamp e
ordinal sobre todas as linhas. Prefixos já consumidos são pulados por posição;
duplicatas legítimas permanecem. Lote cheio volta a ser drenado sem pausa de 20 ms,
mas há no máximo uma consulta de cada tipo por passagem para não monopolizar DOM.
Não há chamadas simultâneas nem threads que inicializem/encerrem outra sessão.

Cada tick_batch preserva campos originais (incluindo time, time_msc, flags,
volume/volume_real e preços), classificação trade/quote e lado derivado.
Trade significa presença de flag LAST ou VOLUME. BUY/SELL exclusivos determinam
lado; BUY e SELL simultaneos ou ambos ausentes permanecem AMBIGUOUS.
Bits adicionais nao anulam BUY/SELL exclusivos: sao preservados em
unknown_flag_mask por tick e unknown_flag_masks/ticks_with_unknown_flags no resumo.
O bit 1024 nao recebe significado. Os flags originais continuam em raw.flags. Preço/volume
inválidos são preservados e contados, sem fabricar valores ou descartar evidência.
Não há inferência de execução a partir do DOM.

Registros incluem UTC local antes/depois da consulta, tempos monotônicos e latência.
Tempos locais de um lote são tempos de consulta/recepção do lote, não de cada negócio.
DOM não possui timestamp de origem: esses dados não comprovam causalidade nem
sincronização exata de mercado. Polls lentos ou escrita no console/disco podem
alongar intervalos; resumo traz intervalos reais e atraso de agendamento.

Resumo: consultas, livros não vazios/vazios/falhas, ticks brutos/cotações/negócios,
negócios válidos/inválidos, flags/lados, timestamps extremos, cursor, contadores
incrementais e resultado da liberação. both_observed requer DOM não vazio e ao
menos um negócio válido com timestamp posterior ao tick inicial. Isso comprova
apenas observação conjunta de dados, não integridade total ou atualidade absoluta
do feed. joint_capture_unproven indica que essa condição não foi observada.
Qualquer erro de execução leva a incomplete. Saída 0 apenas para both_observed.

Falhas do cursor/ticks encerram sem reconexão ou reinício silencioso; erros DOM
são registrados e permitem continuar até o fim do teste, que será incomplete.
A janela de 30 s começa após inicialização/assinatura; não há drenagem final fora
da janela. Pode haver ticks finais ainda não consultados: não é um arquivo de
negócios completo certificado. Não existe settlement delay: prefixo estável e
ordem do feed são premissas; regressões, truncamentos e falta de progresso detectáveis
encerram explicitamente. Encerrar por Ctrl+C executa finally, libera a assinatura
adquirida e encerra MT5; kill forçado não permite essa garantia.

Este processo usa o terminal selecionado automaticamente pelo initialize existente.
Ele não cria isolamento de outros processos que usem o mesmo terminal. Para reduzir
interferência, execute manualmente num ambiente de teste identificado, sem mudar
ou encerrar os streams operacionais a partir deste script.

Testes exclusivamente sintéticos:

```powershell
python -m unittest discover -s panic-scanner/scripts -p "test_mt5_dom_trades_probe.py" -v
```

Cobrem sessão única, cadências, flags, duplicatas, cursor, cotações/negócios,
quantidades inválidas, livro vazio/falha, tick inicial inválido, ausência de avanço
de mercado, interrupção, falha de consulta/assinatura/liberação e consultas lentas.


## Validacao offline da correcao de agressao

A correcao e exclusiva do probe experimental. A funcao operacional
mt5_trade_stream.aggressor_side e o Java permanecem intactos e continuam com a
politica anterior; esta etapa nao corrige a classificacao dos streams operacionais.

Arquivo original: dom-captures/WINZ26-joint-probe-30s.ndjson.
Reclassificacao em memoria pelos raw.flags: 616 ticks, 50 cotacoes e 566 negocios;
206 BUY, 340 SELL, 20 AMBIGUOUS. Resultado esperado confirmado sem divergencia.
Os 20 ambiguos possuem BUY e SELL simultaneamente. Os 50 quotes nao recebem lado.
unknown_flag_mask=1024 nos 616 ticks, sem inferencia de sua semantica.
O campo side antigo permanece AMBIGUOUS no arquivo original; nenhuma linha foi
reescrita. SHA-256 antes/depois:
6a289aeefa5f95ad9c339b10c705ae7b8046cd4d7703ee69d606f86c8e1fe968.

Testes: 4 testes do classificador experimental (flags observados, bits conhecidos,
bits extras e valores invalidos) e 39 testes DOM, incluindo a integracao dos
novos campos no probe, todos aprovados. Comandos:

```powershell
python -m unittest discover -s panic-scanner/scripts -p "test_mt5_experimental_aggressor.py" -v
python -m unittest discover -s panic-scanner/scripts -p "test_mt5_dom*.py" -q
```
