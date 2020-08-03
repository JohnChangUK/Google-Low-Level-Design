package logclient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class GoogleLogClient implements LogClient {

    private final Map<String, Process> processes;
    private final ConcurrentSkipListMap<Long, Process> queue;
    private final List<CompletableFuture<Void>> futures;
    private final Lock lock;
    private final ExecutorService[] taskScheduler;

    public GoogleLogClient() {
        this.processes = new ConcurrentHashMap<>();
        this.queue = new ConcurrentSkipListMap<>();
        this.futures = new CopyOnWriteArrayList<>();
        this.lock = new ReentrantLock();
        this.taskScheduler = new ExecutorService[10];
        for (int i = 0; i < taskScheduler.length; i++) {
            taskScheduler[i] = Executors.newSingleThreadExecutor();
        }
    }

    @Override
    public void start(String processId, long timestamp) {
        taskScheduler[processId.hashCode() % taskScheduler.length].execute(() -> {
            final Process process = new Process(processId, timestamp);
            processes.put(processId, process);
            queue.put(timestamp, process);
        });
    }

    @Override
    public void end(String processId) {
        taskScheduler[processId.hashCode() % taskScheduler.length].execute(() -> {
            lock.lock();
            try {
                long now = System.currentTimeMillis();
                processes.get(processId).setEndTime(now);
                Process process = queue.firstEntry().getValue();
                if (!futures.isEmpty() && process.getId().equals(processId)) {
                    pollNow(process);
                    CompletableFuture<Void> removedResult = futures.remove(0);
                    removedResult.complete(null);
                }
            } finally {
                lock.unlock();
            }
        });
    }

    @Override
    public String poll() {
        lock.lock();
        try {
            final var result = new CompletableFuture<Void>();
            Process process = queue.firstEntry().getValue();
            if (!queue.isEmpty() && process.getEndTime() != -1) {
                pollNow(process);
            } else {
                futures.add(result);
            }
            try {
                result.get(3, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new RuntimeException(e);
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    private void pollNow(Process process) {
        final var logStatement = process.getId() + " started at " + process.getStartTime() +
                " and ended at " + process.getEndTime();
        System.out.println(logStatement);
        processes.remove(process.getId());
        queue.pollFirstEntry();
    }
}
