package br.com.bauzin.crypto.repository;

import br.com.bauzin.crypto.model.CryptoPrice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface CryptoPriceRepository extends MongoRepository<CryptoPrice, String> {

    Optional<CryptoPrice> findFirstBySymbolAndIntervalOrderByTimestampDesc(String symbol, String interval);

    Optional<CryptoPrice> findBySymbolAndIntervalAndTimestamp(String symbol, String interval, Instant timestamp);

    List<CryptoPrice> findBySymbolAndIntervalOrderByTimestampDesc(String symbol, String interval, Pageable pageable);

    List<CryptoPrice> findBySymbolAndIntervalAndTimestampBetweenOrderByTimestampAsc(String symbol,
                                                                                    String interval,
                                                                                    Instant startTime,
                                                                                    Instant endTime);

    List<CryptoPrice> findBySymbolAndIntervalAndTimestampBetween(String symbol,
                                                                 String interval,
                                                                 Instant startTime,
                                                                 Instant endTime);
}
