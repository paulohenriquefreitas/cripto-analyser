package br.com.bauzin.apps.scanner;
import br.com.bauzin.market.panic.panicscanner.application.PanicScannerProperties;
import br.com.bauzin.market.panic.panicscanner.application.entry.DefaultEntryAnalyzer;
import br.com.bauzin.market.panic.panicscanner.application.entry.EntryAnalyzer;
import br.com.bauzin.market.panic.panicscanner.application.entry.PanicScannerEntryProperties;
import br.com.bauzin.market.panic.panicscanner.domain.entry.EntryScoreCalculator;
import br.com.bauzin.market.panic.panicscanner.domain.scoring.MomentumScoringConfig;
import br.com.bauzin.market.panic.panicscanner.domain.scoring.ScoreCalculator;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.MomentumScannerStrategy;
import br.com.bauzin.market.panic.panicscanner.domain.strategy.ScannerStrategy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import java.time.Clock;


@org.springframework.context.annotation.Configuration
public class ScannerComposition {
    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    MomentumScoringConfig momentumScoringConfig(PanicScannerProperties properties) {
        return new MomentumScoringConfig(
                properties.minAverageFinancialVolume20(),
                properties.minimumRsi(),
                properties.maximumRsi(),
                properties.minimumAdx(),
                properties.minimumQualifiedScore(),
                properties.maximumHealthyDistanceFromSma21Percent(),
                properties.preferredRelativeVolume());
    }

    @Bean
    ScoreCalculator scoreCalculator() {
        return new ScoreCalculator();
    }

    @Bean
    ScannerStrategy scannerStrategy(MomentumScoringConfig scoringConfig, ScoreCalculator scoreCalculator) {
        return new MomentumScannerStrategy(scoringConfig, scoreCalculator);
    }

    @Bean
    EntryScoreCalculator entryScoreCalculator() {
        return new EntryScoreCalculator();
    }

    @Bean
    EntryAnalyzer entryAnalyzer(PanicScannerEntryProperties properties, EntryScoreCalculator scoreCalculator) {
        return new DefaultEntryAnalyzer(properties.toConfig(), scoreCalculator);
    }
}
