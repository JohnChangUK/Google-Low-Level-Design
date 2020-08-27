package logclient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class AsyncMapPopulator {

    private final ExecutorService backgroundJobExecutor;

    public AsyncMapPopulator(ExecutorService backgroundJobExecutor) {
        this.backgroundJobExecutor = backgroundJobExecutor;
    }

    public Future<Map<String, Integer>> apply(Map<String, Integer> map) {
        ConcurrentMap<String, Integer> concurrentMap = new ConcurrentHashMap<>(map.size());
        Stream.Builder<CompletableFuture<Void>> incrementingJobs = Stream.builder();
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            String className = entry.getKey();
            Integer oldValue = entry.getValue();
            CompletableFuture.runAsync(() ->
                    concurrentMap.put(className, oldValue + 1), backgroundJobExecutor);
        }

        //Then Apply
        return CompletableFuture.allOf(
                Stream.builder().build().toArray(CompletableFuture[]::new))
                .thenApply(x -> concurrentMap);
    }

    public static void main(String[] args) throws ExecutionException {
        AsyncMapPopulator asyncMapPopulator = new AsyncMapPopulator(Executors.newFixedThreadPool(10));
        Map<String, Integer> hashMap = new LinkedHashMap<>();
        hashMap.put("One", 1);
        hashMap.put("Two", 2);
        hashMap.put("Three", 3);
        hashMap.put("Four", 4);
        hashMap.put("Five", 5);

        int i = Runtime.getRuntime().availableProcessors();
        ForkJoinPool forkJoinPool = new ForkJoinPool(19);

        Future<Map<String, Integer>> apply = asyncMapPopulator.apply(hashMap);
        Map<String, Integer> mapPopudlatedAsync = null;
        try {
            mapPopudlatedAsync = apply.get();
        } catch (InterruptedException e) {
            System.out.println("Err Timeout in Future GET");
        }

        System.out.println(mapPopudlatedAsync);
        asyncMapPopulator.backgroundJobExecutor.shutdownNow();
        try {
            asyncMapPopulator.backgroundJobExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            System.out.println("err in terminating executor");
        }
    }
}
