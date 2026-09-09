package com.creditscore.platform.ingestion;

import com.creditscore.platform.common.CountryCurrencyResolver;
import com.creditscore.platform.identity.business.BusinessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class DataSourceService {

    private final DataSourceRepository dataSourceRepository;
    private final BusinessService businessService;

    public DataSourceService(DataSourceRepository dataSourceRepository, BusinessService businessService) {
        this.dataSourceRepository = dataSourceRepository;
        this.businessService = businessService;
    }

    @Transactional
    public DataSource create(UUID businessId, AdapterType adapterType, String provider) {
        var business = businessService.getOrThrow(businessId);
        String currency = CountryCurrencyResolver.resolve(business.getCountry());
        DataSource dataSource = new DataSource(businessId, adapterType, provider, currency);
        return dataSourceRepository.save(dataSource);
    }

    public List<DataSource> listForBusiness(UUID businessId) {
        return dataSourceRepository.findByBusinessId(businessId);
    }

    public DataSource getOrThrow(UUID dataSourceId) {
        return dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new NoSuchElementException("DataSource not found: " + dataSourceId));
    }
}
