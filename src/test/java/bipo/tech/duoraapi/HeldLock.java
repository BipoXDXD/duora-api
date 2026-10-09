package bipo.tech.duoraapi;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.transaction.support.TransactionTemplate;

/**
 * Uma transação, em outra thread, que pega um lock e o segura até o {@link #close()}. Serve aos testes do teto
 * de espera pelo lock (docs/adr/0006): a requisição do teste espera, desiste com 503 e não grava nada. A
 * transação termina com rollback, então o que ela fez para pegar o lock não fica no banco.
 *
 * <pre>{@code
 * try (var _ = HeldLock.hold(transactionTemplate, () -> lockTheEvent(eventId))) {
 *     register(ana(), eventId).andExpect(status().isServiceUnavailable());
 * }
 * }</pre>
 */
public final class HeldLock implements AutoCloseable {

    private static final long TIMEOUT_SECONDS = 30;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CountDownLatch release = new CountDownLatch(1);
    private final Future<?> holder;

    private HeldLock(TransactionTemplate transactionTemplate, Runnable takeTheLock) {
        var acquired = new CompletableFuture<Void>();
        holder = executor.submit(() -> transactionTemplate.executeWithoutResult(transaction -> {
            try {
                takeTheLock.run();
                acquired.complete(null);
            } catch (RuntimeException e) {
                acquired.completeExceptionally(e);
                throw e;
            }
            awaitRelease();
            transaction.setRollbackOnly();
        }));
        try {
            acquired.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            close();
            throw new IllegalStateException("the transaction did not take the lock", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new IllegalStateException("interrupted while waiting for the lock", e);
        }
    }

    /** Roda {@code takeTheLock} numa transação nova e só volta depois que ela terminou, com o lock em mãos. */
    public static HeldLock hold(TransactionTemplate transactionTemplate, Runnable takeTheLock) {
        return new HeldLock(transactionTemplate, takeTheLock);
    }

    /** Solta o lock e espera a transação terminar. */
    @Override
    public void close() {
        release.countDown();
        try {
            holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("the transaction holding the lock failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }
    }

    private void awaitRelease() {
        try {
            release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
