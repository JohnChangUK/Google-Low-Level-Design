package logger;

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
    public void start(String processId, long timestamp) { //1
        taskScheduler[processId.hashCode() % taskScheduler.length].execute(() -> {
            final Process process = new Process(processId, timestamp);
            processes.put(processId, process);
            queue.put(timestamp, process);
        });
    }

    @Override
    public void end(String processId) { //1
        taskScheduler[processId.hashCode() % taskScheduler.length].execute(() -> {
            lock.lock();//1
            try {
                final long now = System.currentTimeMillis();//1
                processes.get(processId).setEndTime(now);//1
                if (!futures.isEmpty() && queue.firstEntry().getValue().getId().equals(processId)) {
                    pollNow();
                    final var result = futures.remove(0);
                    result.complete(null);
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
            if (!queue.isEmpty() && queue.firstEntry().getValue().getEndTime() != -1) {
                pollNow();
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

    private String pollNow() {
        final Process process = queue.firstEntry().getValue();
        final var logStatement = process.getId() + " started at " + process.getStartTime() + " and ended at " + process.getEndTime();
        System.out.println(logStatement);
        processes.remove(process.getId());
        queue.pollFirstEntry();
        return logStatement;
    }
}
