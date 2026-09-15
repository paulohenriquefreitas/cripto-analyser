package br.com.bauzin.market.panic.panicscanner.api;

import jakarta.validation.ConstraintViolationException;
import br.com.bauzin.market.panic.panicscanner.application.MarketDataProviderException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class PanicScannerExceptionHandler {

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({ConstraintViolationException.class, IllegalArgumentException.class})
    public ValidationError handleValidation(Exception exception) {
        return new ValidationError(List.of(exception.getMessage()));
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ValidationError handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        return new ValidationError(exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage() == null ? error.getField() + " is invalid" : error.getDefaultMessage())
                .toList());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ValidationError handleUnreadableMessage(HttpMessageNotReadableException exception) {
        return new ValidationError(List.of("request body is required"));
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(MarketDataProviderException.class)
    public ValidationError handleMarketDataProvider(MarketDataProviderException exception) {
        return new ValidationError(List.of(exception.getMessage()));
    }

    public record ValidationError(List<String> errors) {
    }
}
