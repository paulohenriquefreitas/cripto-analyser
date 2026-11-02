# Terceira condicao: pavio minimo de 30 pontos em C3

Criterio vigente: DOW_LEG_MINIMUM_WICK_TEST_RETURN_M1_V4. Atualizado conforme correcao explicita do usuario em 02/11/2026: **30 pontos**, equivalentes a **6 ticks WIN**, para o pavio; afastamento da entrada permanece **20 pontos**. O limite de 150 pontos usado na primeira proposta foi substituido.

## Medida e causalidade

BUY: `min(abertura_C3, fechamento_parcial_C3) - minima_observada_C3`.
SELL: `maxima_observada_C3 - max(abertura_C3, fechamento_parcial_C3)`.

O minimo de 30 pontos e inclusivo: 29 nao passa, 30 e 31 passam. Nos precos reais em incrementos de cinco pontos, 25 nao passa e 35 passa. A amplitude do teste sozinha nao basta: abertura 1000, minima 960 e fechamento parcial 980 significam excursao 40, mas pavio inferior 20, insuficiente. O fechamento parcial e conhecido ao fechar o M1 observado; nao e o fechamento futuro de C3.

C1 exige alvo/contexto e C2 negacao pelo fechamento como antes. C3 precisa tentar a continuacao do fluxo original. A ordem so fica disponivel quando o teste e o pavio minimo sao conhecidos. `rejectionWick` guarda pontos, ticks, minimo, horario e observacao parcial imutavel; evento PAVIO_REJEICAO_QUALIFICADO permite auditar a decisao.

Referencia da ordem permanece a abertura de C3: BUY abertura +20 pontos, SELL abertura -20. Nao foi trocada para extrema parcial dinamica. A transcricao da aula 2, 00:52:29-00:52:47, cita 20 pontos e a minima corrente do 13; nao se afirma que essa minima equivale sempre a abertura. A referencia inicial foi mantida nesta alteracao de unidade.

Outro M1 posterior ao posicionamento precisa alcancar o gatilho. O minuto que qualificou o pavio nao comprova entrada. Se a qualificacao chegar com o preco observado ja no/alem do gatilho, a ordem nao e criada retroativamente: INDETERMINADO. Lacunas nao qualificam a ordem. C3 sem pavio suficiente observado termina sem ordem, com PAVIO_REJEICAO_INSUFICIENTE. O OHLC M5 final nao inventa a sequencia intrabar.

## Interface e compatibilidade

Resumo prioriza pontos: pavio minimo **30 pontos (6 ticks)** e afastamento **20 pontos**. Auditoria mostra tamanho e horario do pavio observado. API mantem as unidades explicitas: `minRejectionTicks: 6`, `minimumPoints: 30`, `orderOffsetPoints: 20`. Snapshots antigos continuam validos com os parametros que efetivamente usaram.

Parameters.win() agora usa 6 ticks e 20 pontos. Politica explicita com minimo zero continua disponivel para testes historicos isolados. Nenhum criterio de Dow, Fibonacci, DMI, CHOP ou candidatos 2+1 foi alterado.

## Reproducao e historico

[Comparacao atual com as evidencias nativas de 06/10 e 30/10](../../frontend/src/features/threecandles/validation/pattern-wick-v4/comparison.json). Unidade: C1 + direcao + preco do gatilho; referencias confluentes nao sao operacoes independentes.

[Comparacao anterior usando 150 pontos, preservada apenas como historico](../../frontend/src/features/threecandles/validation/pattern-wick-v4/comparison-previous-150-points.json). Seus numeros nao descrevem o minimo atual.

Testes cobrem 25/29/30/31/35 pontos, distincao entre pavio e excursao, qualificacao tardia, ordem de eventos, compra/venda espelhadas, lacunas, impossibilidade de entrada retroativa e manutencao do afastamento de 20 pontos. Casos nativos usam o parametro atual e validam cada colocacao/alcance. O frontend recalcula a evidencia e rejeita unidade, tamanho, horario ou minimo adulterados.

O modulo continua hipotetico: sem ordens reais, fills, stop ou P&L. Menos ou mais sinais nao comprovam acerto. Reiniciar backend e recriar Replay para receber o novo parametro. A observacao por M1 continua limitada ao fechamento do minuto; ticks em tempo real nao foram implementados. application.yml do crypto-analyzer nao precisou ser alterado.
