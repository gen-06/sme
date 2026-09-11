package com.creditscore.platform.seed;

import com.creditscore.platform.identity.business.Business;
import com.creditscore.platform.identity.business.BusinessRepository;
import com.creditscore.platform.identity.consumer.Consumer;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import com.creditscore.platform.identity.consumer.ConsumerScope;
import com.creditscore.platform.identity.auth.ApiKeyGenerator;
import com.creditscore.platform.identity.auth.ApiKeyHasher;
import com.creditscore.platform.ingestion.AdapterType;
import com.creditscore.platform.ingestion.DataSource;
import com.creditscore.platform.ingestion.DataSourceService;
import com.creditscore.platform.scoring.ScoringService;
import com.creditscore.platform.sync.DataSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Set;

/**
 * Demo/seed data only — deliberately not a Flyway migration (Flyway governs schema,
 * not throwaway rows). This is the only thing that provisions an API-key Consumer:
 * {@code POST /api/v1/admin/consumers} provisions additional consumers at runtime, but
 * issues OAuth2 client credentials rather than API keys. So running with
 * {@code SPRING_PROFILES_ACTIVE=seed} is still required for the API-key path to work
 * locally; see README.
 *
 * <p>The {@code seed} profile doubles as the marker for "this is a local dev or demo
 * instance" — see
 * {@link com.creditscore.platform.identity.auth.PlatformAdminTokenStartupCheck}, which
 * only tolerates the published default platform-admin token under this same profile.
 */
@Component
@Profile("seed")
public class SeedDataRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataRunner.class);

    private final BusinessRepository businessRepository;
    private final DataSourceService dataSourceService;
    private final ConsumerRepository consumerRepository;
    private final DataSyncService dataSyncService;
    private final ScoringService scoringService;

    public SeedDataRunner(BusinessRepository businessRepository, DataSourceService dataSourceService,
                           ConsumerRepository consumerRepository, DataSyncService dataSyncService,
                           ScoringService scoringService) {
        this.businessRepository = businessRepository;
        this.dataSourceService = dataSourceService;
        this.consumerRepository = consumerRepository;
        this.dataSyncService = dataSyncService;
        this.scoringService = scoringService;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (businessRepository.count() > 0) {
            log.info("Seed data already present, skipping.");
            return;
        }

        Business kenyaBusiness = businessRepository.save(new Business(
                "Jua Kali Hardware", "KE", "Retail", "PVT-KE-2021-001", LocalDate.now().minusYears(3)));
        Business nigeriaBusiness = businessRepository.save(new Business(
                "Lagos Fresh Foods", "NG", "Food & Beverage", "RC-1234567", LocalDate.now().minusYears(2)));
        Business ghanaBusiness = businessRepository.save(new Business(
                "Accra Textiles Ltd", "GH", "Manufacturing", "BN-987654", LocalDate.now().minusMonths(8)));

        DataSource kenyaDataSource = dataSourceService.create(kenyaBusiness.getId(), AdapterType.MOBILE_MONEY, "M-PESA");
        DataSource nigeriaDataSource = dataSourceService.create(nigeriaBusiness.getId(), AdapterType.MOBILE_MONEY, "OPay");
        dataSourceService.create(ghanaBusiness.getId(), AdapterType.MOBILE_MONEY, "MTN MoMo");

        dataSyncService.sync(kenyaDataSource);
        scoringService.computeAndPersistScore(kenyaBusiness.getId());

        dataSyncService.sync(nigeriaDataSource);
        scoringService.computeAndPersistScore(nigeriaBusiness.getId());
        // Ghana business is left unsynced on purpose, to demo the "no score yet" state.

        String apiKey = ApiKeyGenerator.generate();
        Consumer demoConsumer = new Consumer(
                "Demo Lender Co", "demo@lender.test",
                ApiKeyHasher.hash(apiKey), ApiKeyGenerator.displayPrefix(apiKey),
                Set.of(ConsumerScope.values()));
        consumerRepository.save(demoConsumer);

        log.info("==================================================================");
        log.info("Seed data loaded.");
        log.info("Demo consumer API key (shown once, not recoverable): {}", apiKey);
        log.info("Kenya business id (synced, has score):   {}", kenyaBusiness.getId());
        log.info("Nigeria business id (synced, has score): {}", nigeriaBusiness.getId());
        log.info("Ghana business id (unsynced, no score):  {}", ghanaBusiness.getId());
        log.info("==================================================================");
    }
}
