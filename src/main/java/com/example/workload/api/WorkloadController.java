package com.example.workload.api;

import com.example.workload.engine.WorkloadEngine;
import com.example.workload.service.WorkloadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api")
public class WorkloadController {
    private final WorkloadService service;

    // Helps prevent the JIT from optimizing our "custom marker" method away.
    private static volatile long CUSTOM_PROFILING_SINK = 0;
    private static final List<byte[]> CUSTOM_ALLOC_RETAIN = new ArrayList<>();

    public WorkloadController(WorkloadService service) {
        this.service = service;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    @GetMapping("/workload/status")
    public ResponseEntity<?> status() {
        WorkloadEngine.Snapshot s = service.status();
        if (s == null) return ResponseEntity.ok(new StatusResponse(false, null));
        return ResponseEntity.ok(StatusResponse.from(s));
    }

    @PostMapping("/workload/start")
    public ResponseEntity<?> start(@RequestBody(required = false) StartRequest req) {
        try {
            WorkloadEngine.Config cfg = StartRequest.toConfig(req);
            WorkloadEngine.Snapshot s = service.start(cfg);
            return ResponseEntity.ok(StatusResponse.from(s));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of("already_running", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of("bad_request", e.getMessage()));
        }
    }

    @PostMapping("/workload/stop")
    public ResponseEntity<?> stop() {
        try {
            WorkloadEngine.Snapshot s = service.stop();
            return ResponseEntity.ok(StatusResponse.from(s));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("not_found", e.getMessage()));
        }
    }

    /**
     * Custom endpoint intentionally designed to show your app methods in profilers (Pyroscope/async-profiler).
     *
     * Example:
     *   GET /api/custom/profile?cpuMs=5000&sleepMs=0&label=demo
     *
     * - cpuMs: do CPU work for N milliseconds (busy loop)
     * - sleepMs: then sleep for N milliseconds (shows up in wall-clock profiles)
     * - label: just an extra string to help you locate this run in logs/UI filters
     */
    @GetMapping("/custom/profile")
    public ResponseEntity<?> customProfile(
            @RequestParam(name = "cpuMs", required = false, defaultValue = "2000") long cpuMs,
            @RequestParam(name = "sleepMs", required = false, defaultValue = "0") long sleepMs,
            @RequestParam(name = "label", required = false, defaultValue = "custom") String label
    ) {
        if (cpuMs < 0 || sleepMs < 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of("bad_request", "cpuMs and sleepMs must be >= 0"));
        }

        long startNs = System.nanoTime();
        long cpuStartNs = startNs;

        if (cpuMs > 0) {
            customProfilingMarkerCpuSpin(label, cpuMs);
        }

        long cpuEndNs = System.nanoTime();

        if (sleepMs > 0) {
            try {
                customProfilingMarkerSleep(label, sleepMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ErrorResponse.of("interrupted", "Interrupted while sleeping"));
            }
        }

        long endNs = System.nanoTime();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("label", label);
        resp.put("cpuMsRequested", cpuMs);
        resp.put("sleepMsRequested", sleepMs);
        resp.put("cpuMsActual", Math.max(0, (cpuEndNs - cpuStartNs) / 1_000_000));
        resp.put("totalMsActual", Math.max(0, (endNs - startNs) / 1_000_000));
        resp.put("sink", CUSTOM_PROFILING_SINK);
        return ResponseEntity.ok(resp);
    }

    private static void customProfilingMarkerCpuSpin(String label, long cpuMs) {
        // Intentionally "CPU-ish" work that won't get optimized out easily.
        // The method name is made unique so you can search for it in the profiler UI.
        long deadline = System.nanoTime() + cpuMs * 1_000_000L;
        long x = (label != null ? label.hashCode() : 0) ^ 0x9E3779B97F4A7C15L;

        while (System.nanoTime() < deadline) {
            x = customProfilingMarkerMix64(x);
            x ^= customProfilingMarkerRotateLeft(x, 13);
            x += 0xD1B54A32D192ED03L;
            // Hint the CPU; also adds a recognizable frame in some profiles.
            Thread.onSpinWait();
        }

        CUSTOM_PROFILING_SINK ^= x;
    }

    private static void customProfilingMarkerSleep(String label, long sleepMs) throws InterruptedException {
        // This shows up mainly in WALL profiles, not CPU profiles.
        CUSTOM_PROFILING_SINK ^= (label != null ? label.length() : 0);
        Thread.sleep(sleepMs);
    }

    private static long customProfilingMarkerMix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    private static long customProfilingMarkerRotateLeft(long v, int n) {
        return (v << n) | (v >>> (64 - n));
    }

    /**
     * Allocation-heavy endpoint to make your app frames (com.example...) show up in
     * allocation profiles (memory - alloc_*).
     *
     * Example:
     *   GET /api/custom/alloc?mb=256&blockKb=64&retain=true
     */
    @GetMapping("/custom/alloc")
    public ResponseEntity<?> customAlloc(
            @RequestParam(name = "mb", required = false, defaultValue = "128") int mb,
            @RequestParam(name = "blockKb", required = false, defaultValue = "64") int blockKb,
            @RequestParam(name = "retain", required = false, defaultValue = "false") boolean retain
    ) {
        if (mb < 0 || blockKb <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of("bad_request", "mb must be >= 0 and blockKb must be > 0"));
        }

        int blockBytes = blockKb * 1024;
        long targetBytes = (long) mb * 1024L * 1024L;
        long allocated = 0;
        int blocks = 0;

        while (allocated < targetBytes) {
            byte[] b = new byte[blockBytes];
            // touch memory so it isn't optimized away
            b[0] = (byte) blocks;
            CUSTOM_PROFILING_SINK += b[0];
            allocated += b.length;
            blocks++;

            if (retain) {
                synchronized (CUSTOM_ALLOC_RETAIN) {
                    CUSTOM_ALLOC_RETAIN.add(b);
                }
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("mbRequested", mb);
        resp.put("blockKb", blockKb);
        resp.put("retain", retain);
        resp.put("blocksAllocated", blocks);
        resp.put("bytesAllocated", allocated);
        resp.put("retainedBlocks", retainedBlocks());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/custom/alloc/clear")
    public ResponseEntity<?> customAllocClear() {
        int cleared;
        synchronized (CUSTOM_ALLOC_RETAIN) {
            cleared = CUSTOM_ALLOC_RETAIN.size();
            CUSTOM_ALLOC_RETAIN.clear();
        }
        return ResponseEntity.ok(Map.of("ok", true, "clearedBlocks", cleared));
    }

    private static int retainedBlocks() {
        synchronized (CUSTOM_ALLOC_RETAIN) {
            return CUSTOM_ALLOC_RETAIN.size();
        }
    }

    public record StartRequest(
            Integer durationSec,
            Integer warmupSec,
            Integer threads,
            Integer reportEverySec,
            Integer memRetainMB,
            Integer memBlockKB,
            Integer matrixSize,
            Integer hashPayloadKB,
            Integer churnMBps,
            Integer pointerChaseMB,
            Boolean printThreadCpu,
            Long seed
    ) {
        static WorkloadEngine.Config toConfig(StartRequest req) {
            WorkloadEngine.Config d = WorkloadEngine.Config.defaults();
            if (req == null) return d;

            int duration = or(req.durationSec, d.durationSec);
            int warmup = or(req.warmupSec, d.warmupSec);
            int threads = or(req.threads, d.threads);
            int report = or(req.reportEverySec, d.reportEverySec);
            int memRetain = or(req.memRetainMB, d.memRetainMB);
            int memBlock = or(req.memBlockKB, d.memBlockKB);
            int matrix = or(req.matrixSize, d.matrixSize);
            int hashKB = or(req.hashPayloadKB, d.hashPayloadKB);
            int churn = or(req.churnMBps, d.churnMBps);
            int ptr = or(req.pointerChaseMB, d.pointerChaseMB);
            boolean cpu = req.printThreadCpu != null ? req.printThreadCpu : d.printThreadCpu;
            long seed = req.seed != null ? req.seed : d.seed;

            // basic validation / normalization
            if (duration <= 0) throw new IllegalArgumentException("durationSec must be > 0");
            if (warmup < 0) throw new IllegalArgumentException("warmupSec must be >= 0");
            if (threads <= 0) throw new IllegalArgumentException("threads must be > 0");
            if (report < 0) throw new IllegalArgumentException("reportEverySec must be >= 0");

            return new WorkloadEngine.Config(
                    duration,
                    warmup,
                    threads,
                    report,
                    Math.max(0, memRetain),
                    Math.max(4, memBlock),
                    Math.max(64, matrix),
                    Math.max(1, hashKB),
                    Math.max(1, churn),
                    Math.max(1, ptr),
                    cpu,
                    seed
            );
        }

        private static int or(Integer v, int def) {
            return v == null ? def : v;
        }
    }

    public record StatusResponse(
            boolean running,
            Snapshot snapshot
    ) {
        static StatusResponse from(WorkloadEngine.Snapshot s) {
            return new StatusResponse(
                    s.running,
                    new Snapshot(
                            s.jobId,
                            s.jobUuid,
                            s.phase,
                            s.startEpochMs,
                            s.elapsedMs,
                            s.plannedEndEpochMs,
                            s.primesOps,
                            s.matrixOps,
                            s.hashOps,
                            s.churnBytes,
                            s.pointerChaseOps,
                            s.heapUsedMB,
                            s.heapTotalMB,
                            s.heapMaxMB,
                            WorkloadEngine.blackhole(),
                            s.config,
                            s.error
                    )
            );
        }

        public record Snapshot(
                long jobId,
                String jobUuid,
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
                long blackhole,
                String config,
                String error
        ) {}
    }

    public record ErrorResponse(Error error) {
        static ErrorResponse of(String code, String message) {
            return new ErrorResponse(new Error(code, message));
        }

        public record Error(String code, String message) {}
    }
}

