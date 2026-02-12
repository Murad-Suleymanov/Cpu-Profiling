package com.example.workload.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Service with deeply nested method calls for CPU profiling demonstrations.
 *
 * Call tree (approximate CPU share):
 *
 *   processOrder()
 *    ├── validateInput()            ~10%
 *    │    ├── parsePayload()
 *    │    └── checkConstraints()
 *    ├── enrichData()               ~20%
 *    │    ├── lookupCache()
 *    │    └── computeScore()
 *    │         └── heavyScoreCalc()
 *    ├── transform()                ~30%
 *    │    ├── serializeToBytes()
 *    │    └── compressPayload()
 *    │         └── deflateBytes()
 *    └── persist()                  ~40%
 *         ├── buildIndex()
 *         │    └── sortEntries()
 *         └── writeToStore()
 *              └── checksumVerify()
 */
@Service
public class DeepProfilingService {

    // Prevents JIT from optimizing away our work
    private static volatile long SINK = 0;

    /**
     * Main entry point — processes a simulated order with nested CPU-intensive steps.
     *
     * @param durationMs total CPU time budget (distributed across steps)
     * @param seed       random seed for reproducibility
     * @return timing breakdown of each step
     */
    public Map<String, Object> processOrder(long durationMs, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        Map<String, Object> timings = new LinkedHashMap<>();
        long totalStart = System.nanoTime();

        // ~10% CPU
        long t0 = System.nanoTime();
        long validated = validateInput(rnd, (long) (durationMs * 0.10));
        timings.put("validateInput_ms", ms(t0));

        // ~20% CPU
        t0 = System.nanoTime();
        long enriched = enrichData(rnd, (long) (durationMs * 0.20));
        timings.put("enrichData_ms", ms(t0));

        // ~30% CPU
        t0 = System.nanoTime();
        long transformed = transform(rnd, (long) (durationMs * 0.30));
        timings.put("transform_ms", ms(t0));

        // ~40% CPU
        t0 = System.nanoTime();
        long persisted = persist(rnd, (long) (durationMs * 0.40));
        timings.put("persist_ms", ms(t0));

        SINK ^= validated ^ enriched ^ transformed ^ persisted;

        timings.put("total_ms", ms(totalStart));
        timings.put("sink", SINK);
        return timings;
    }

    // ─── VALIDATE (~10%) ────────────────────────────────────────────

    private long validateInput(SplittableRandom rnd, long budgetMs) {
        long half = Math.max(1, budgetMs / 2);

        long a = parsePayload(rnd, half);
        long b = checkConstraints(rnd, budgetMs - half);

        return a ^ b;
    }

    private long parsePayload(SplittableRandom rnd, long ms) {
        // Simulate JSON parsing — tight string hashing loop
        long deadline = System.nanoTime() + ms * 1_000_000L;
        long hash = rnd.nextLong();
        while (System.nanoTime() < deadline) {
            hash = (hash ^ (hash >>> 16)) * 0x85ebca6bL;
            hash = (hash ^ (hash >>> 13)) * 0xc2b2ae35L;
            hash ^= (hash >>> 16);
        }
        return hash;
    }

    private long checkConstraints(SplittableRandom rnd, long ms) {
        // Simulate constraint validation — prime checking
        long deadline = System.nanoTime() + ms * 1_000_000L;
        long result = 0;
        int n = 1_000_000 + rnd.nextInt(1_000_000);
        while (System.nanoTime() < deadline) {
            if (isPrime(n)) result += n;
            n += 2;
        }
        return result;
    }

    // ─── ENRICH (~20%) ──────────────────────────────────────────────

    private long enrichData(SplittableRandom rnd, long budgetMs) {
        long quarter = Math.max(1, budgetMs / 4);

        long a = lookupCache(rnd, quarter);
        long b = computeScore(rnd, budgetMs - quarter);

        return a ^ b;
    }

    private long lookupCache(SplittableRandom rnd, long ms) {
        // Simulate cache lookup — random array access (pointer chasing)
        int size = 256 * 1024;
        int[] table = new int[size];
        for (int i = 0; i < size; i++) table[i] = rnd.nextInt(size);

        long deadline = System.nanoTime() + ms * 1_000_000L;
        int idx = rnd.nextInt(size);
        long result = 0;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 1000; i++) {
                idx = table[idx];
                result ^= idx;
            }
        }
        return result;
    }

    private long computeScore(SplittableRandom rnd, long budgetMs) {
        long half = Math.max(1, budgetMs / 3);

        // Light score calc
        long a = lightScoreCalc(rnd, half);
        // Heavy score calc (deeper nesting)
        long b = heavyScoreCalc(rnd, budgetMs - half);

        return a ^ b;
    }

    private long lightScoreCalc(SplittableRandom rnd, long ms) {
        // Floating point math
        long deadline = System.nanoTime() + ms * 1_000_000L;
        double score = rnd.nextDouble();
        while (System.nanoTime() < deadline) {
            score = Math.sin(score) * Math.cos(score * 1.1) + Math.sqrt(Math.abs(score) + 1.0);
            score = score * 0.999 + 0.001;
        }
        return Double.doubleToLongBits(score);
    }

    private long heavyScoreCalc(SplittableRandom rnd, long ms) {
        // SHA-256 based scoring — shows up clearly in flamegraphs
        long deadline = System.nanoTime() + ms * 1_000_000L;
        MessageDigest md = sha256();
        byte[] buf = new byte[4096];
        rnd.nextBytes(buf);

        long result = 0;
        while (System.nanoTime() < deadline) {
            byte[] digest = md.digest(buf);
            for (int i = 0; i < digest.length; i++) result = result * 131 + (digest[i] & 0xff);
            buf[0] ^= (byte) result;
        }
        return result;
    }

    // ─── TRANSFORM (~30%) ───────────────────────────────────────────

    private long transform(SplittableRandom rnd, long budgetMs) {
        long third = Math.max(1, budgetMs / 3);

        long a = serializeToBytes(rnd, third);
        long b = compressPayload(rnd, budgetMs - third);

        return a ^ b;
    }

    private long serializeToBytes(SplittableRandom rnd, long ms) {
        // Simulate serialization — string building + encoding
        long deadline = System.nanoTime() + ms * 1_000_000L;
        long result = 0;
        while (System.nanoTime() < deadline) {
            StringBuilder sb = new StringBuilder(4096);
            for (int i = 0; i < 100; i++) {
                sb.append("{\"key\":\"").append(rnd.nextLong()).append("\",\"val\":")
                  .append(rnd.nextDouble()).append("},");
            }
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            result ^= bytes.length;
        }
        return result;
    }

    private long compressPayload(SplittableRandom rnd, long budgetMs) {
        long half = Math.max(1, budgetMs / 2);

        // First half: prepare data
        long a = prepareCompressionInput(rnd, half);
        // Second half: actual deflate
        long b = deflateBytes(rnd, budgetMs - half);

        return a ^ b;
    }

    private long prepareCompressionInput(SplittableRandom rnd, long ms) {
        // Build repetitive patterns (compressible data)
        long deadline = System.nanoTime() + ms * 1_000_000L;
        long result = 0;
        while (System.nanoTime() < deadline) {
            byte[] data = new byte[8192];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (rnd.nextInt(26) + 'a');
            }
            // Sort to create more compressible patterns
            Arrays.sort(data);
            result ^= data[0] ^ data[data.length - 1];
        }
        return result;
    }

    private long deflateBytes(SplittableRandom rnd, long ms) {
        // Real compression — Deflater shows up clearly in profiles
        long deadline = System.nanoTime() + ms * 1_000_000L;
        byte[] input = new byte[16384];
        byte[] output = new byte[16384];
        rnd.nextBytes(input);
        // Make it somewhat compressible
        for (int i = 0; i < input.length; i++) input[i] = (byte) (input[i] % 64);

        long result = 0;
        while (System.nanoTime() < deadline) {
            Deflater def = new Deflater(Deflater.BEST_COMPRESSION);
            def.setInput(input);
            def.finish();
            int compressed = def.deflate(output);
            def.end();
            result ^= compressed;
        }
        return result;
    }

    // ─── PERSIST (~40%) ─────────────────────────────────────────────

    private long persist(SplittableRandom rnd, long budgetMs) {
        long half = Math.max(1, budgetMs / 2);

        long a = buildIndex(rnd, half);
        long b = writeToStore(rnd, budgetMs - half);

        return a ^ b;
    }

    private long buildIndex(SplittableRandom rnd, long budgetMs) {
        long half = Math.max(1, budgetMs / 2);

        // First half: generate entries
        long a = generateEntries(rnd, half);
        // Second half: sort
        long b = sortEntries(rnd, budgetMs - half);

        return a ^ b;
    }

    private long generateEntries(SplittableRandom rnd, long ms) {
        long deadline = System.nanoTime() + ms * 1_000_000L;
        long result = 0;
        while (System.nanoTime() < deadline) {
            long[] keys = new long[10_000];
            for (int i = 0; i < keys.length; i++) keys[i] = rnd.nextLong();
            result ^= keys[0] ^ keys[keys.length - 1];
        }
        return result;
    }

    private long sortEntries(SplittableRandom rnd, long ms) {
        // Sorting — Arrays.sort shows up in profiles
        long deadline = System.nanoTime() + ms * 1_000_000L;
        long result = 0;
        while (System.nanoTime() < deadline) {
            int[] data = new int[50_000];
            for (int i = 0; i < data.length; i++) data[i] = rnd.nextInt();
            Arrays.sort(data);
            result ^= data[0] ^ data[data.length / 2] ^ data[data.length - 1];
        }
        return result;
    }

    private long writeToStore(SplittableRandom rnd, long budgetMs) {
        long half = Math.max(1, budgetMs / 2);

        long a = encodeRecords(rnd, half);
        long b = checksumVerify(rnd, budgetMs - half);

        return a ^ b;
    }

    private long encodeRecords(SplittableRandom rnd, long ms) {
        // Simulate record encoding — matrix multiplication
        long deadline = System.nanoTime() + ms * 1_000_000L;
        int n = 64;
        double[][] a = new double[n][n];
        double[][] b = new double[n][n];
        double[][] c = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                a[i][j] = rnd.nextDouble();
                b[i][j] = rnd.nextDouble();
            }

        long result = 0;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < n; i++) {
                Arrays.fill(c[i], 0);
                for (int k = 0; k < n; k++) {
                    double aik = a[i][k];
                    for (int j = 0; j < n; j++) c[i][j] += aik * b[k][j];
                }
            }
            result ^= Double.doubleToLongBits(c[0][0]);
            a[0][0] = a[0][0] * 0.999 + 0.001;
        }
        return result;
    }

    private long checksumVerify(SplittableRandom rnd, long ms) {
        // CRC32 checksumming — distinct from SHA-256 in flamegraph
        long deadline = System.nanoTime() + ms * 1_000_000L;
        byte[] buf = new byte[8192];
        rnd.nextBytes(buf);

        long result = 0;
        CRC32 crc = new CRC32();
        while (System.nanoTime() < deadline) {
            crc.reset();
            crc.update(buf);
            result ^= crc.getValue();
            buf[0] ^= (byte) result;
        }
        return result;
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private static boolean isPrime(int n) {
        if (n < 2) return false;
        if (n % 2 == 0) return n == 2;
        if (n % 3 == 0) return n == 3;
        int r = (int) Math.sqrt(n);
        for (int f = 5; f <= r; f += 6) {
            if (n % f == 0 || n % (f + 2) == 0) return false;
        }
        return true;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
