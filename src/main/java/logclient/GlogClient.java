package logclient;

import java.util.ArrayList;
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

public class GlogClient implements LogClient {

    private final Map<String, Process> processes;
    private final ConcurrentSkipListMap<Long, Process> queue;
    private final List<CompletableFuture<Void>> futures;
    private final Lock lock;
    private final List<ExecutorService> taskScheduler;

    public GlogClient() {
        this.processes = new ConcurrentHashMap<>();
        this.queue = new ConcurrentSkipListMap<>();
        this.futures = new CopyOnWriteArrayList<>();
        this.lock = new ReentrantLock();
        this.taskScheduler = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            taskScheduler.add(i, Executors.newSingleThreadExecutor());
        }
    }


    @Override
    public void start(String processId, long timestamp) {
        taskScheduler.get(processId.hashCode() % taskScheduler.size()).execute(() -> {
            Process process = new Process(processId, timestamp);
            processes.put(processId, process);
            queue.put(timestamp, process);
        });
    }

    @Override
    public void end(String processId) {
        taskScheduler.get(processes.hashCode() % taskScheduler.size()).execute(() -> {
            lock.lock();
            try {
                long now = System.currentTimeMillis();
                processes.get(processId).setEndTime(now);
                Process process = queue.firstEntry().getValue();
                if (!futures.isEmpty() && process.getId().equals(processId)) {
                    pollNow(process);
                    CompletableFuture<Void> removed = futures.remove(0);
                    removed.complete(null);
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
            CompletableFuture<Void> result = new CompletableFuture<>();
            Process process = queue.firstEntry().getValue();
            if (!queue.isEmpty() && process.getEndTime() != -1) {
                pollNow(process);
            } else {
                futures.add(result);
            }
            try {
                result.get(3, TimeUnit.SECONDS);
            } catch (ExecutionException | InterruptedException | TimeoutException e) {
                throw new RuntimeException(e);
            }

            return null;
        } finally {
            lock.unlock();
        }
    }

    private void pollNow(Process process) {
        var logStatement = process.getId() + " started at " +
                process.getStartTime() + " and ended at " + process.getEndTime();
        System.out.println(logStatement);
        processes.remove(process.getId());
        queue.pollFirstEntry();
    }
}
