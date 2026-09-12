package com.creditscore.platform.batch;

import com.creditscore.platform.ingestion.ConnectionStatus;
import com.creditscore.platform.ingestion.DataSource;
import com.creditscore.platform.sync.DataSyncService;
import jakarta.persistence.EntityManagerFactory;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

/**
 * Scheduled, all-businesses reconciliation. Reserved for periodic catch-up sync of
 * every connected data source; the per-request {@code POST /businesses/{id}/sync}
 * endpoint calls {@code DataSyncService} directly instead of launching a Job per
 * request (Batch's chunking/restart machinery adds overhead a single-adapter mock
 * sync completing in milliseconds doesn't need).
 */
@Configuration
public class SyncReconciliationJobConfig {

    private static final int CHUNK_SIZE = 20;

    /**
     * Backs {@code @SchedulerLock} on {@code SyncJobScheduler.runScheduledReconciliation()}
     * with a real cross-instance lock in the {@code shedlock} table (V12 migration) — without
     * it, every instance launches its own full reconciliation on every cron tick, with no
     * coordination between them.
     */
    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(jdbcTemplate);
    }

    @Bean
    public Job syncReconciliationJob(JobRepository jobRepository, Step syncReconciliationStep) {
        return new JobBuilder("syncReconciliationJob", jobRepository)
                .start(syncReconciliationStep)
                .build();
    }

    @Bean
    public Step syncReconciliationStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                        JpaPagingItemReader<DataSource> connectedDataSourceReader,
                                        ItemProcessor<DataSource, DataSource> syncItemProcessor) {
        return new StepBuilder("syncReconciliationStep", jobRepository)
                .<DataSource, DataSource>chunk(CHUNK_SIZE, transactionManager)
                .reader(connectedDataSourceReader)
                .processor(syncItemProcessor)
                .writer(noOpWriter())
                .build();
    }

    @Bean
    public JpaPagingItemReader<DataSource> connectedDataSourceReader(EntityManagerFactory entityManagerFactory) {
        return new JpaPagingItemReaderBuilder<DataSource>()
                .name("connectedDataSourceReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("select d from DataSource d where d.connectionStatus = :status "
                        + "order by d.lastSyncedAt asc nulls first")
                .parameterValues(Map.of("status", ConnectionStatus.CONNECTED))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    @Bean
    public ItemProcessor<DataSource, DataSource> syncItemProcessor(DataSyncService dataSyncService) {
        return dataSource -> {
            dataSyncService.sync(dataSource);
            return dataSource;
        };
    }

    private ItemWriter<DataSource> noOpWriter() {
        // DataSyncService already persists everything (transactions + lastSyncedAt)
        // inside the processor step; nothing left for the writer to do.
        return chunk -> {
        };
    }
}
