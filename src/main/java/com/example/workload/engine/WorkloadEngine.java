package com.example.workload.engine;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * CPU + memory workload engine.
 *
 * Qeyd: burada heç bir profiler/agent qoşulmur. Sadəcə workload yaradır.
 */
public final class WorkloadEngine {
    // JIT-in loop-ları tam "ölü kod" kimi atmasının qarşısı üçün.
    private static volatile long BLACKHOLE = 0;

    private WorkloadEngine() {}

    public static long blackhole() {
        return BLACKHOLE;
    }

    public static void runBlockingWithWarmup(Config cfg) throws InterruptedException {
        System.out.println("Workload starting with config: " + cfg);
        if (cfg.warmupSec > 0) {
            runPhase("warmup", cfg.withDuration(cfg.warmupSec), new Counters(), new AtomicBoolean(false));
        }
        runPhase("run", cfg, new Counters(), new AtomicBoolean(false));
        System.out.println("Done. BLACKHOLE=" + BLACKHOLE);
    }

    public static Job startJob(long jobId, Config cfg) {
        Job j = new Job(jobId, cfg);
        j.start();
        return j;
    }

    public static final class Snapshot {
        public final long jobId;
        public final String jobUuid;
        public final boolean running;
        public final String phase;
        public final long startEpochMs;
        public final long elapsedMs;
        public final long plannedEndEpochMs;
        public final long primesOps;
        public final long matrixOps;
        public final long hashOps;
        public final long churnBytes;
        public final long pointerChaseOps;
        public final long heapUsedMB;
        public final long heapTotalMB;
        public final long heapMaxMB;
        public final String error;
        public final String config;

        Snapshot(long jobId,
                 String jobUuid,
                 boolean running,
                 String phase,
                 long startEpochMs,
                 long elapsedMs,
                 long plannedEndEpochMs,
                 long primesOps,
                 long matrixOps,
                 long hashOps,
                 long churnBytes,
                 long pointerChaseOps,
                 long heapUsedMB,
                 long heapTotalMB,
                 long heapMaxMB,
                 String error,
                 String config) {
            this.jobId = jobId;
            this.jobUuid = jobUuid;
            this.running = running;
            this.phase = phase;
            this.startEpochMs = startEpochMs;
            this.elapsedMs = elapsedMs;
            this.plannedEndEpochMs = plannedEndEpochMs;
            this.primesOps = primesOps;
            this.matrixOps = matrixOps;
            this.hashOps = hashOps;
            this.churnBytes = churnBytes;
            this.pointerChaseOps = pointerChaseOps;
            this.heapUsedMB = heapUsedMB;
            this.heapTotalMB = heapTotalMB;
            this.heapMaxMB = heapMaxMB;
            this.error = error;
            this.config = config;
        }
    }

    public static final class Job {
        private final long id;
        private final String uuid = UUID.randomUUID().toString();
        private final Config cfg;
        private final AtomicBoolean stop = new AtomicBoolean(false);
        private final Counters runCounters = new Counters();

        private volatile boolean running = false;
        private volatile String phase = "init";
        private volatile Instant startedAt;
        private volatile Instant plannedEnd;
        private volatile Throwable error;

        private Thread thread;

        Job(long id, Config cfg) {
            this.id = id;
            this.cfg = cfg;
        }

        void start() {
            if (thread != null) return;
            thread = new Thread(this::runInternal);
            thread.setName("job-" + id);
            thread.setDaemon(true);
            thread.start();
        }

        public void stop() {
            stop.set(true);
        }

        public boolean isRunning() {
            return running;
        }

        public Snapshot snapshot() {
            long usedMB = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
            long totalMB = Runtime.getRuntime().totalMemory() / (1024 * 1024);
            long maxMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);

            Instant s = startedAt;
            long startMs = s == null ? 0 : s.toEpochMilli();
            long elapsed = s == null ? 0 : Duration.between(s, Instant.now()).toMillis();
            long endMs = plannedEnd == null ? 0 : plannedEnd.toEpochMilli();
            String err = error == null ? null : (error.getClass().getName() + ": " + error.getMessage());

            return new Snapshot(
                    id,
                    uuid,
                    running,
                    phase,
                    startMs,
                    elapsed,
                    endMs,
                    runCounters.opsPrime.sum(),
                    runCounters.opsMatrix.sum(),
                    runCounters.opsHash.sum(),
                    runCounters.opsChurn.sum(),
                    runCounters.opsPointerChase.sum(),
                    usedMB,
                    totalMB,
                    maxMB,
                    err,
                    cfg.toString()
            );
        }

        private void runInternal() {
            running = true;
            startedAt = Instant.now();
            try {
                if (cfg.warmupSec > 0 && !stop.get()) {
                    phase = "warmup";
                    runPhase("warmup", cfg.withDuration(cfg.warmupSec), new Counters(), stop);
                }
                if (!stop.get()) {
                    phase = "run";
                    plannedEnd = Instant.now().plusSeconds(cfg.durationSec);
                    runPhase("run", cfg, runCounters, stop);
                }
            } catch (Throwable t) {
                error = t;
            } finally {
                running = false;
                if (stop.get()) {
                    phase = "stopped";
                } else if (error != null) {
                    phase = "error";
                } else {
                    phase = "done";
                }
            }
        }
    }

    static final class Counters {
        final LongAdder opsPrime = new LongAdder();
        final LongAdder opsMatrix = new LongAdder();
        final LongAdder opsHash = new LongAdder();
        final LongAdder opsChurn = new LongAdder(); // bytes
        final LongAdder opsPointerChase = new LongAdder();
    }

    private static void runPhase(String phase, Config cfg, Counters counters, AtomicBoolean stop) throws InterruptedException {
        System.out.println("\n== Phase: " + phase + " (" + cfg.durationSec + "s) ==");

        Instant start = Instant.now();
        Instant end = start.plusSeconds(cfg.durationSec);

        // Memory retention: heap-də qalacaq bloklar
        MemoryHog hog = new MemoryHog(cfg.memRetainMB, cfg.memBlockKB, cfg.seed);
        hog.allocateAndTouch();

        // Worker pool
        ExecutorService workers = Executors.newFixedThreadPool(cfg.threads, r -> {
            Thread t = new Thread(r);
            t.setName("worker-" + t.getId());
            t.setDaemon(true);
            return t;
        });

        CountDownLatch started = new CountDownLatch(cfg.threads);
        CountDownLatch finished = new CountDownLatch(cfg.threads);

        WorkloadPlan plan = WorkloadPlan.defaultMix(cfg.threads);

        for (int i = 0; i < cfg.threads; i++) {
            int idx = i;
            workers.execute(() -> {
                Thread.currentThread().setName("worker-" + idx + "-" + plan.kindFor(idx).name().toLowerCase(Locale.ROOT));
                started.countDown();
                try {
                    switch (plan.kindFor(idx)) {
                        case PRIMES -> new PrimeWorker(cfg.seed + idx, counters.opsPrime, stop).run();
                        case MATRIX -> new MatrixWorker(cfg.seed + idx, cfg.matrixSize, counters.opsMatrix, stop).run();
                        case HASH -> new HashWorker(cfg.seed + idx, cfg.hashPayloadKB, counters.opsHash, stop).run();
                        case CHURN -> new AllocationChurnWorker(cfg.seed + idx, cfg.churnMBps, counters.opsChurn, stop).run();
                        case POINTER_CHASE -> new PointerChaseWorker(cfg.seed + idx, cfg.pointerChaseMB, counters.opsPointerChase, stop).run();
                    }
                } finally {
                    finished.countDown();
                }
            });
        }

        started.await();

        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("monitor");
            t.setDaemon(true);
            return t;
        });

        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        if (cfg.printThreadCpu && mx.isThreadCpuTimeSupported() && !mx.isThreadCpuTimeEnabled()) {
            try {
                mx.setThreadCpuTimeEnabled(true);
            } catch (SecurityException ignored) {
                // default: cpu time off qalacaq
            }
        }

        AtomicLong lastReportNs = new AtomicLong(System.nanoTime());
        AtomicLong lastPrime = new AtomicLong(0);
        AtomicLong lastMatrix = new AtomicLong(0);
        AtomicLong lastHash = new AtomicLong(0);
        AtomicLong lastChurn = new AtomicLong(0);
        AtomicLong lastPtr = new AtomicLong(0);

        monitor.scheduleAtFixedRate(() -> {
            try {
                if (cfg.reportEverySec <= 0) return;

                long nowNs = System.nanoTime();
                long prevNs = lastReportNs.getAndSet(nowNs);
                double dt = Math.max(1e-9, (nowNs - prevNs) / 1_000_000_000.0);

                long p = counters.opsPrime.sum();
                long m = counters.opsMatrix.sum();
                long h = counters.opsHash.sum();
                long c = counters.opsChurn.sum();
                long pc = counters.opsPointerChase.sum();

                long dp = p - lastPrime.getAndSet(p);
                long dm = m - lastMatrix.getAndSet(m);
                long dh = h - lastHash.getAndSet(h);
                long dc = c - lastChurn.getAndSet(c);
                long dpc = pc - lastPtr.getAndSet(pc);

                long usedMB = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
                long totalMB = Runtime.getRuntime().totalMemory() / (1024 * 1024);
                long maxMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);

                String cpu = "";
                if (cfg.printThreadCpu && mx.isThreadCpuTimeSupported() && mx.isThreadCpuTimeEnabled()) {
                    cpu = " | cpuTime=enabled";
                }

                System.out.printf(
                        Locale.ROOT,
                        "t=%4.1fs | ops/s primes=%8.0f matrix=%8.0f hash=%8.0f churn(bytes/s)=%10.0f ptr=%8.0f | heap=%d/%d MB (max %d)%s%n",
                        Duration.between(start, Instant.now()).toMillis() / 1000.0,
                        dp / dt, dm / dt, dh / dt, dc / dt, dpc / dt,
                        usedMB, totalMB, maxMB,
                        cpu
                );

                // Retained memory-i "touch" edək ki real istifadə kimi görünsün.
                BLACKHOLE ^= hog.touchSample();
            } catch (Throwable t) {
                // Monitor heç vaxt crash etməsin.
            }
        }, 1, Math.max(1, cfg.reportEverySec), TimeUnit.SECONDS);

        while (!stop.get() && Instant.now().isBefore(end)) {
            Thread.sleep(50);
        }

        // phase time-up oldu, ya da external stop gəldi.
        // External stop flag-i burada set etmirik (yoxsa növbəti phase-lər dərhal dayanar).
        workers.shutdownNow(); // interrupt workers
        finished.await(30, TimeUnit.SECONDS);
        monitor.shutdownNow();

        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        System.out.println("Phase finished in " + elapsedMs + "ms. Totals: " +
                "primes=" + counters.opsPrime.sum() +
                ", matrix=" + counters.opsMatrix.sum() +
                ", hash=" + counters.opsHash.sum() +
                ", churnBytes=" + counters.opsChurn.sum() +
                ", ptr=" + counters.opsPointerChase.sum());
    }

    enum Kind { PRIMES, MATRIX, HASH, CHURN, POINTER_CHASE }

    static final class WorkloadPlan {
        private final Kind[] kinds;

        private WorkloadPlan(Kind[] kinds) {
            this.kinds = kinds;
        }

        static WorkloadPlan defaultMix(int threads) {
            // Mix: 40% primes, 20% matrix, 20% hash, 10% churn, 10% pointer chase
            Kind[] k = new Kind[threads];
            for (int i = 0; i < threads; i++) {
                double x = (i + 0.5) / Math.max(1.0, threads);
                if (x < 0.40) k[i] = Kind.PRIMES;
                else if (x < 0.60) k[i] = Kind.MATRIX;
                else if (x < 0.80) k[i] = Kind.HASH;
                else if (x < 0.90) k[i] = Kind.CHURN;
                else k[i] = Kind.POINTER_CHASE;
            }
            return new WorkloadPlan(k);
        }

        Kind kindFor(int threadIdx) {
            return kinds[threadIdx % kinds.length];
        }
    }

    static final class PrimeWorker implements Runnable {
        private final SplittableRandom rnd;
        private final LongAdder ops;
        private final AtomicBoolean stop;

        PrimeWorker(long seed, LongAdder ops, AtomicBoolean stop) {
            this.rnd = new SplittableRandom(seed);
            this.ops = ops;
            this.stop = stop;
        }

        @Override public void run() {
            while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                int base = 2_000_000 + rnd.nextInt(0, 2_000_000);
                int span = 25_000 + rnd.nextInt(0, 25_000);
                int count = countPrimesInRange(base, base + span);
                ops.add(span);
                BLACKHOLE ^= (count * 1315423911L);
            }
        }

        private static int countPrimesInRange(int lo, int hi) {
            int cnt = 0;
            for (int n = Math.max(2, lo); n <= hi; n++) {
                if ((n & 2047) == 0 && Thread.currentThread().isInterrupted()) break;
                if (isPrime(n)) cnt++;
            }
            return cnt;
        }

        private static boolean isPrime(int n) {
            if (n % 2 == 0) return n == 2;
            if (n % 3 == 0) return n == 3;
            int r = (int) Math.sqrt(n);
            for (int f = 5; f <= r; f += 6) {
                if (n % f == 0 || n % (f + 2) == 0) return false;
            }
            return true;
        }
    }

    static final class MatrixWorker implements Runnable {
        private final SplittableRandom rnd;
        private final int n;
        private final LongAdder ops;
        private final AtomicBoolean stop;

        MatrixWorker(long seed, int n, LongAdder ops, AtomicBoolean stop) {
            this.rnd = new SplittableRandom(seed);
            this.n = Math.max(64, n);
            this.ops = ops;
            this.stop = stop;
        }

        @Override public void run() {
            double[][] a = new double[n][n];
            double[][] b = new double[n][n];
            double[][] c = new double[n][n];

            fill(a);
            fill(b);

            while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                mul(a, b, c);
                ops.increment();

                int i = rnd.nextInt(n);
                int j = rnd.nextInt(n);
                a[i][j] = a[i][j] * 0.999999 + 1e-6;
                BLACKHOLE ^= Double.doubleToLongBits(c[i][j]);
            }
        }

        private void fill(double[][] m) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    m[i][j] = rnd.nextDouble(-1.0, 1.0);
                }
            }
        }

        private void mul(double[][] a, double[][] b, double[][] c) {
            for (int i = 0; i < n; i++) {
                if ((i & 7) == 0 && Thread.currentThread().isInterrupted()) return;
                double[] ci = c[i];
                Arrays.fill(ci, 0.0);
                for (int k = 0; k < n; k++) {
                    double aik = a[i][k];
                    double[] bk = b[k];
                    for (int j = 0; j < n; j++) {
                        ci[j] += aik * bk[j];
                    }
                }
            }
        }
    }

    static final class HashWorker implements Runnable {
        private final SplittableRandom rnd;
        private final int payloadBytes;
        private final LongAdder ops;
        private final AtomicBoolean stop;
        private final MessageDigest digest;

        HashWorker(long seed, int payloadKB, LongAdder ops, AtomicBoolean stop) {
            this.rnd = new SplittableRandom(seed);
            this.payloadBytes = Math.max(256, payloadKB * 1024);
            this.ops = ops;
            this.stop = stop;
            this.digest = sha256();
        }

        @Override public void run() {
            byte[] buf = new byte[payloadBytes];
            while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                rnd.nextBytes(buf);
                byte[] out = digest.digest(buf);
                long x = 0;
                for (int i = 0; i < out.length; i++) x = (x * 131) + (out[i] & 0xff);
                BLACKHOLE ^= x;
                ops.increment();
            }
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static final class AllocationChurnWorker implements Runnable {
        private final SplittableRandom rnd;
        private final int targetMBps;
        private final LongAdder bytes;
        private final AtomicBoolean stop;

        AllocationChurnWorker(long seed, int targetMBps, LongAdder bytes, AtomicBoolean stop) {
            this.rnd = new SplittableRandom(seed);
            this.targetMBps = Math.max(1, targetMBps);
            this.bytes = bytes;
            this.stop = stop;
        }

        @Override public void run() {
            final int ring = 256; // power of two
            byte[][] keep = new byte[ring][];
            int p = 0;

            long lastNs = System.nanoTime();
            long budgetBytes = 0;

            while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                long now = System.nanoTime();
                long dt = Math.max(0, now - lastNs);
                lastNs = now;

                budgetBytes += (long) (dt * (targetMBps * 1024.0 * 1024.0) / 1_000_000_000.0);
                long made = 0;
                while (budgetBytes >= 4096) {
                    if (Thread.currentThread().isInterrupted() || stop.get()) break;
                    int sz = 1024 + rnd.nextInt(0, 64 * 1024);
                    byte[] arr = new byte[sz];
                    for (int i = 0; i < arr.length; i += 64) arr[i] = (byte) i;
                    keep[p] = arr;
                    p = (p + 1) & (ring - 1);
                    budgetBytes -= sz;
                    made += sz;
                }
                bytes.add(made);
                byte[] last = keep[p == 0 ? ring - 1 : p - 1];
                if (last != null) BLACKHOLE ^= last.length;
            }
        }
    }

    static final class PointerChaseWorker implements Runnable {
        static final class Node {
            Node next;
            long v;
        }

        private final SplittableRandom rnd;
        private final int mb;
        private final LongAdder ops;
        private final AtomicBoolean stop;

        PointerChaseWorker(long seed, int mb, LongAdder ops, AtomicBoolean stop) {
            this.rnd = new SplittableRandom(seed);
            this.mb = Math.max(1, mb);
            this.ops = ops;
            this.stop = stop;
        }

        @Override public void run() {
            int nodes = (mb * 1024 * 1024) / 32; // təxmini
            nodes = Math.max(50_000, nodes);
            Node[] arr = new Node[nodes];
            for (int i = 0; i < nodes; i++) {
                Node n = new Node();
                n.v = rnd.nextLong();
                arr[i] = n;
            }
            for (int i = 0; i < nodes; i++) {
                arr[i].next = arr[rnd.nextInt(nodes)];
            }

            Node cur = arr[rnd.nextInt(nodes)];
            while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                long x = 0;
                for (int i = 0; i < 10_000; i++) {
                    if ((i & 1023) == 0 && Thread.currentThread().isInterrupted()) break;
                    cur = cur.next;
                    x ^= cur.v;
                }
                BLACKHOLE ^= x;
                ops.increment();
            }
        }
    }

    static final class MemoryHog {
        private final int retainMB;
        private final int blockKB;
        private final SplittableRandom rnd;
        private final ArrayList<byte[]> blocks = new ArrayList<>();

        MemoryHog(int retainMB, int blockKB, long seed) {
            this.retainMB = Math.max(0, retainMB);
            this.blockKB = Math.max(4, blockKB);
            this.rnd = new SplittableRandom(seed ^ 0x9E3779B97F4A7C15L);
        }

        void allocateAndTouch() {
            int totalKB = retainMB * 1024;
            int blocksCount = totalKB / blockKB;
            for (int i = 0; i < blocksCount; i++) {
                byte[] b = new byte[blockKB * 1024];
                rnd.nextBytes(b);
                for (int j = 0; j < b.length; j += 4096) b[j] ^= (byte) j;
                blocks.add(b);
            }
            System.out.println("Retained blocks: " + blocks.size() + " (~" + retainMB + "MB)");
            BLACKHOLE ^= touchSample();
        }

        long touchSample() {
            if (blocks.isEmpty()) return 0;
            long s = 0;
            for (int i = 0; i < 32; i++) {
                byte[] b = blocks.get(rnd.nextInt(blocks.size()));
                s = (s * 131) + (b[rnd.nextInt(b.length)] & 0xff);
            }
            return s;
        }
    }

    public static final class Config {
        public final int durationSec;
        public final int warmupSec;
        public final int threads;
        public final int reportEverySec;
        public final int memRetainMB;
        public final int memBlockKB;
        public final int matrixSize;
        public final int hashPayloadKB;
        public final int churnMBps;
        public final int pointerChaseMB;
        public final boolean printThreadCpu;
        public final long seed;

        public Config(int durationSec,
                      int warmupSec,
                      int threads,
                      int reportEverySec,
                      int memRetainMB,
                      int memBlockKB,
                      int matrixSize,
                      int hashPayloadKB,
                      int churnMBps,
                      int pointerChaseMB,
                      boolean printThreadCpu,
                      long seed) {
            this.durationSec = durationSec;
            this.warmupSec = warmupSec;
            this.threads = threads;
            this.reportEverySec = reportEverySec;
            this.memRetainMB = memRetainMB;
            this.memBlockKB = memBlockKB;
            this.matrixSize = matrixSize;
            this.hashPayloadKB = hashPayloadKB;
            this.churnMBps = churnMBps;
            this.pointerChaseMB = pointerChaseMB;
            this.printThreadCpu = printThreadCpu;
            this.seed = seed;
        }

        public static Config defaults() {
            int cores = Runtime.getRuntime().availableProcessors();
            return new Config(
                    30,
                    10,
                    Math.max(1, cores),
                    2,
                    256,
                    256,
                    192,
                    256,
                    256,
                    128,
                    false,
                    42L
            );
        }

        public Config withDuration(int sec) {
            return new Config(sec, 0, threads, reportEverySec, memRetainMB, memBlockKB, matrixSize, hashPayloadKB, churnMBps, pointerChaseMB, printThreadCpu, seed);
        }

        @Override public String toString() {
            return "durationSec=" + durationSec +
                    ", warmupSec=" + warmupSec +
                    ", threads=" + threads +
                    ", reportEverySec=" + reportEverySec +
                    ", memRetainMB=" + memRetainMB +
                    ", memBlockKB=" + memBlockKB +
                    ", matrixSize=" + matrixSize +
                    ", hashPayloadKB=" + hashPayloadKB +
                    ", churnMBps=" + churnMBps +
                    ", pointerChaseMB=" + pointerChaseMB +
                    ", printThreadCpu=" + printThreadCpu +
                    ", seed=" + seed;
        }
    }
}

