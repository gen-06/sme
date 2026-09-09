package com.creditscore.platform.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SyncJobScheduler {

    private final JobLauncher jobLauncher;
    private final Job syncReconciliationJob;

    public SyncJobScheduler(JobLauncher jobLauncher, Job syncReconciliationJob) {
        this.jobLauncher = jobLauncher;
        this.syncReconciliationJob = syncReconciliationJob;
    }

    @Scheduled(cron = "${app.batch.sync-cron}")
    public void runScheduledReconciliation() throws Exception {
        var params = new JobParametersBuilder()
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();
        jobLauncher.run(syncReconciliationJob, params);
    }
}
