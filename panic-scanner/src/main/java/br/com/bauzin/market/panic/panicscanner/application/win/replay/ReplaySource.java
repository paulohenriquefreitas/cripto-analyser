package br.com.bauzin.market.panic.panicscanner.application.win.replay;

import java.io.IOException;
import java.util.function.Consumer;

/** Pull-to-consumer streaming source. Implementations retain source order and multiplicity. */
@FunctionalInterface
public interface ReplaySource<T> extends AutoCloseable {
    void stream(Consumer<T> consumer) throws IOException, InterruptedException;

    @Override
    default void close() throws IOException {}
}
