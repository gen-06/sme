package com.creditscore.platform.batch;

import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code JobParametersBuilder} keys each run on {@code System.currentTimeMillis()}, so
 * Spring Batch's own duplicate-{@code JobInstance} protection (keyed on identifying
 * parameters in the shared {@code BATCH_JOB_INSTANCE} table) never applies here — every
 * call gets a distinct instance by design. {@code @SchedulerLock} is what actually
 * ensures only one application instance runs a given cron tick, for as long as a run
 * completes within {@code lockAtMostFor} — a run that takes longer has its lock expire
 * while still in progress, and a second instance's next tick can then start
 * concurrently. {@code LockAssert.assertLocked()} throws {@code IllegalStateException}
 * for any caller outside a lock context, including a future {@code @SpringBootTest} or
 * a manual admin-triggered run of this method; ShedLock's escape hatch for that case is
 * {@code LockAssert.TestHelper.makeAllAssertsPass(true)}.
 */
@Component
public class SyncJobScheduler {

    private final JobLauncher jobLauncher;
    private final Job syncReconciliationJob;

    public SyncJobScheduler(JobLauncher jobLauncher, Job syncReconciliationJob) {
        this.jobLauncher = jobLauncher;
        this.syncReconciliationJob = syncReconciliationJob;
    }

    @Scheduled(cron = "${app.batch.sync-cron}")
    @SchedulerLock(name = "syncReconciliationJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runScheduledReconciliation() throws Exception {
        LockAssert.assertLocked();
        var params = new JobParametersBuilder()
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();
        jobLauncher.run(syncReconciliationJob, params);
    }
}
