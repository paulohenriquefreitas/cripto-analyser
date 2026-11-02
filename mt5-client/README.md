# mt5-client

Biblioteca Maven compartilhada para consultas finitas ao MetaTrader 5 e seus modelos de origem.

- Contem `Mt5ProcessClient`, `Mt5Candle`, `Mt5Tick` e `Mt5Symbol`.
- Nao depende de Scanner, WIN M5 ou 3 Candles.
- Nao possui main Spring Boot, stream, scheduler ou conexao iniciada ao carregar o JAR.
- Uma consulta explicita usa o Python/MetaTrader5 ja empregado no repositorio.
- Mantem o namespace Java anterior para preservar imports e testes existentes.

Os quatro fontes foram movidos sem alteracao de conteudo. A comparacao SHA-256 esta em `docs/application-split/mt5-client-extraction.json`.

O aplicativo consumidor controla configuracao, ciclo de vida e ativacao de captura. Compartilhar este JAR nao exige executar outro backend nem configurar token entre processos.
