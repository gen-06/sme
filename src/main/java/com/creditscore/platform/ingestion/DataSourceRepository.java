package com.creditscore.platform.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DataSourceRepository extends JpaRepository<DataSource, UUID> {

    List<DataSource> findByBusinessId(UUID businessId);

    List<DataSource> findByConnectionStatus(ConnectionStatus connectionStatus);
}
