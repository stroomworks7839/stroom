package stroom.index.lucene980;

import stroom.dictionary.api.WordListProvider;
import stroom.expression.api.DateTimeSettings;
import stroom.index.impl.IndexShardSearchConfig;
import stroom.index.impl.IndexShardWriterCache;
import stroom.index.impl.LuceneShardSearcher;
import stroom.index.shared.IndexFieldsMap;
import stroom.query.api.v2.ExpressionOperator;
import stroom.query.api.v2.QueryKey;
import stroom.search.impl.SearchConfig;
import stroom.task.api.ExecutorProvider;
import stroom.task.api.TaskContextFactory;
import stroom.util.io.PathCreator;

import org.apache.lucene980.search.IndexSearcher;

import javax.inject.Inject;
import javax.inject.Provider;

class Lucene980ShardSearcherFactory {

    private final IndexShardWriterCache indexShardWriterCache;
    private final Provider<IndexShardSearchConfig> shardSearchConfigProvider;
    private final ExecutorProvider executorProvider;
    private final TaskContextFactory taskContextFactory;
    private final PathCreator pathCreator;
    private final WordListProvider dictionaryStore;
    private final Provider<SearchConfig> searchConfigProvider;

    @Inject
    Lucene980ShardSearcherFactory(final IndexShardWriterCache indexShardWriterCache,
                                  final Provider<IndexShardSearchConfig> shardSearchConfigProvider,
                                  final ExecutorProvider executorProvider,
                                  final TaskContextFactory taskContextFactory,
                                  final PathCreator pathCreator,
                                  final WordListProvider dictionaryStore,
                                  final Provider<SearchConfig> searchConfigProvider) {
        this.indexShardWriterCache = indexShardWriterCache;
        this.shardSearchConfigProvider = shardSearchConfigProvider;
        this.executorProvider = executorProvider;
        this.taskContextFactory = taskContextFactory;
        this.pathCreator = pathCreator;
        this.dictionaryStore = dictionaryStore;
        this.searchConfigProvider = searchConfigProvider;
    }

    public LuceneShardSearcher create(final ExpressionOperator expression,
                                      final IndexFieldsMap indexFieldsMap,
                                      final DateTimeSettings dateTimeSettings,
                                      final QueryKey queryKey) {
        IndexSearcher.setMaxClauseCount(searchConfigProvider.get().getMaxBooleanClauseCount());
        return new Lucene980ShardSearcher(
                indexShardWriterCache,
                shardSearchConfigProvider.get(),
                executorProvider,
                taskContextFactory,
                pathCreator,
                indexFieldsMap,
                expression,
                dictionaryStore,
                dateTimeSettings,
                queryKey);
    }
}
