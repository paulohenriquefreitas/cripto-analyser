package br.com.bauzin.crypto.client;

import java.util.List;

public record LoxBrokerUdfResponse(
        String s,
        List<Long> t,
        List<String> o,
        List<String> h,
        List<String> l,
        List<String> c,
        List<String> v) {
}
