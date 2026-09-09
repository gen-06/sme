package com.creditscore.platform.ingestion;

/**
 * The one interface every data source integration implements — mobile money,
 * e-commerce, accounting software, POS, and whatever market-specific source comes
 * next. Adding a new source or market means adding one new implementation of this
 * interface in its own {@code ingestion.<source>} package; nothing in
 * {@code normalization}, {@code scoring}, or {@code api} changes.
 */
public interface DataAdapter {

    AdapterType getType();

    SyncResult sync(DataSource dataSource, SyncContext context);
}
