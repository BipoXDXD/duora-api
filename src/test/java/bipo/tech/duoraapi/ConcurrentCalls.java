package bipo.tech.duoraapi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.test.web.servlet.ResultActions;

/**
 * Chamadas que começam no mesmo instante, para provar que a disputa termina consistente. Todas as threads
 * esperam numa trava e partem juntas; sem isso a primeira terminaria antes de a última começar e o teste nunca
 * veria a corrida.
 */
public final class ConcurrentCalls {

    /** Teto de espera de cada chamada: acima disso há deadlock, e o teste deve falhar, não pendurar. */
    private static final long TIMEOUT_SECONDS = 30;

    private ConcurrentCalls() {
    }

    /** Uma requisição ao MockMvc, para rodar numa thread à parte. */
    @FunctionalInterface
    public interface Request {

        ResultActions perform() throws Exception;

    }

    /** Roda as chamadas ao mesmo tempo e devolve os resultados na ordem em que foram dadas. */
    public static <T> List<T> together(List<Callable<T>> calls) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(calls.size())) {
            var futures = calls.stream()
                    .map(call -> executor.submit(() -> {
                        start.await();
                        return call.call();
                    }))
                    .toList();
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) {
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    /** Roda a mesma chamada {@code times} vezes ao mesmo tempo. */
    public static <T> List<T> sameCallTogether(int times, Callable<T> call) throws Exception {
        return together(Collections.nCopies(times, call));
    }

    /** O status HTTP da resposta da requisição, que é o que as corridas conferem. */
    public static Callable<Integer> statusCodeOf(Request request) {
        return () -> request.perform().andReturn().getResponse().getStatus();
    }

}
