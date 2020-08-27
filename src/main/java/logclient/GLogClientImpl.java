package logclient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class GLogClientImpl implements LogClient {

    private final Map<String, Process> processes;
    private final ConcurrentSkipListMap<Long, List<Process>> queue;
    private final BlockingQueue<CompletableFuture<String>> pendingPolls;
    private final Lock lock;
    private final ExecutorService[] taskScheduler;

    public GLogClientImpl(int threads) {
        this.processes = new ConcurrentHashMap<>();
        this.queue = new ConcurrentSkipListMap<>();
        this.pendingPolls = new LinkedBlockingQueue<>();
        this.lock = new ReentrantLock();
        this.taskScheduler = new ExecutorService[threads];
        for (int i = 0; i < taskScheduler.length; i++) {
            taskScheduler[i] = Executors.newSingleThreadExecutor();
        }
    }


    @Override
    public void start(String processId, long timestamp) {
        taskScheduler[processId.hashCode() % taskScheduler.length].execute(() -> {
            Process process = new Process(processId, timestamp);
            processes.put(processId, process);
            queue.putIfAbsent(timestamp, new CopyOnWriteArrayList<>());
            queue.get(timestamp).add(process);
        });
    }

    @Override
    public void end(String processId) {
        taskScheduler[processId.hashCode() % taskScheduler.length].execute(() -> {
            processes.get(processId).setEndTime(System.currentTimeMillis());
            lock.lock();
            try {
                String result;
                while (!pendingPolls.isEmpty() && (result = pollNow()) != null) {
                    pendingPolls.take().complete(result);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }
        });
    }

    @Override
    public String poll() {
        CompletableFuture<String> result = new CompletableFuture<>();
        lock.lock();
        try {
            try {
                String logStatement;
                if (!pendingPolls.isEmpty()) {
                    pendingPolls.offer(result);
                } else if ((logStatement = pollNow()) != null) {
                    return logStatement;
                } else {
                    pendingPolls.offer(result);
                }
            } finally {
                lock.unlock();
            }
            return result.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    private String pollNow() {
        if (!queue.isEmpty()) {
            for (Process earliest : queue.firstEntry().getValue()) {
                if (earliest.getEndTime() != -1) {
                    queue.firstEntry().getValue().remove(earliest);
                    if (queue.firstEntry().getValue().isEmpty()) {
                        queue.pollFirstEntry();
                    }
                    processes.remove(earliest.getId());
                    var logStatement = "task " + earliest.getId() + " started at: "
                            + earliest.getStartTime() + " and ended at: " + earliest.getEndTime();
                    System.out.println(logStatement);
                    return logStatement;
                }
            }
        }
        return null;
    }
}
