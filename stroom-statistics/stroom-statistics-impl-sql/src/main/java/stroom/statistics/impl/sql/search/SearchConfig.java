package stroom.statistics.impl.sql.search;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import stroom.util.cache.CacheConfig;
import stroom.util.shared.IsConfig;

import javax.inject.Singleton;
import java.util.concurrent.TimeUnit;

@Singleton
public class SearchConfig implements IsConfig {
    private static final int DEFAULT_ROWS_IN_BATCH = 5_000;

    private String storeSize = "1000000,100,10,1";
    private int resultHandlerBatchSize = DEFAULT_ROWS_IN_BATCH;
    private int maxResults = 100000;
    private int fetchSize = 5000;
    private CacheConfig searchResultCache = new CacheConfig.Builder()
            .maximumSize(10000L)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();

    @JsonPropertyDescription("The maximum number of search results to keep in memory at each level.")
    public String getStoreSize() {
        return storeSize;
    }

    public void setStoreSize(final String storeSize) {
        this.storeSize = storeSize;
    }

    @JsonPropertyDescription("The number of database rows to pass to the result handler")
    public int getResultHandlerBatchSize() {
        return resultHandlerBatchSize;
    }

    public void setResultHandlerBatchSize(final int resultHandlerBatchSize) {
        this.resultHandlerBatchSize = resultHandlerBatchSize;
    }

    @JsonPropertyDescription("The maximum number of records that can be returned from the statistics DB in a single query prior to aggregation")
    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(final int maxResults) {
        this.maxResults = maxResults;
    }

    @JsonPropertyDescription("Gives the JDBC driver a hint as to the number of rows that should be fetched from the database when more rows are needed for ResultSet objects generated by this Statement. Depends on 'useCursorFetch=true' being set in the JDBC connect string. If not set, the JDBC driver's default will be used.")
    public int getFetchSize() {
        return fetchSize;
    }

    public void setFetchSize(final int fetchSize) {
        this.fetchSize = fetchSize;
    }

    public CacheConfig getSearchResultCache() {
        return searchResultCache;
    }

    public void setSearchResultCache(final CacheConfig searchResultCache) {
        this.searchResultCache = searchResultCache;
    }

    @Override
    public String toString() {
        return "SearchConfig{" +
                "storeSize='" + storeSize + '\'' +
                ", resultHandlerBatchSize=" + resultHandlerBatchSize +
                ", maxResults=" + maxResults +
                ", fetchSize=" + fetchSize +
                ", searchResultCache=" + searchResultCache +
                '}';
    }
}
