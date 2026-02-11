package com.example.workload.service;

import com.example.workload.engine.WorkloadEngine;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class WorkloadService {
    private final AtomicLong jobSeq = new AtomicLong(1);
    private final AtomicReference<WorkloadEngine.Job> current = new AtomicReference<>(null);

    public synchronized WorkloadEngine.Snapshot start(WorkloadEngine.Config cfg) {
        WorkloadEngine.Job existing = current.get();
        if (existing != null && existing.isRunning()) {
            throw new IllegalStateException("Job already running");
        }
        long id = jobSeq.getAndIncrement();
        WorkloadEngine.Job job = WorkloadEngine.startJob(id, cfg);
        current.set(job);
        return job.snapshot();
    }

    public synchronized WorkloadEngine.Snapshot stop() {
        WorkloadEngine.Job job = current.get();
        if (job == null) {
            throw new IllegalStateException("No job");
        }
        job.stop();
        return job.snapshot();
    }

    public WorkloadEngine.Snapshot status() {
        WorkloadEngine.Job job = current.get();
        if (job == null) return null;
        return job.snapshot();
    }
}

