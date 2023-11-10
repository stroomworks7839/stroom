package stroom.index.lucene553;

import stroom.index.lucene553.SearchExpressionQueryBuilder.SearchExpressionQuery;
import stroom.index.shared.IndexField;
import stroom.query.api.v2.SearchRequest;
import stroom.search.extraction.FieldValue;
import stroom.search.impl.SearchException;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import org.apache.lucene553.analysis.Analyzer;
import org.apache.lucene553.analysis.TokenStream;
import org.apache.lucene553.index.IndexableField;
import org.apache.lucene553.index.memory.MemoryIndex;
import org.apache.lucene553.search.IndexSearcher;
import org.apache.lucene553.search.TopDocs;

import java.io.IOException;
import java.util.List;
import javax.inject.Inject;

class MemoryIndexImpl implements stroom.search.extraction.MemoryIndex {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(MemoryIndexImpl.class);

    private final SearchExpressionQueryCache searchExpressionQueryCache;

    @Inject
    public MemoryIndexImpl(final SearchExpressionQueryCache searchExpressionQueryCache) {
        this.searchExpressionQueryCache = searchExpressionQueryCache;
    }

    @Override
    public boolean match(final SearchRequest searchRequest, final List<FieldValue> fieldValues) {
        final MemoryIndex memoryIndex = new MemoryIndex();
        for (final FieldValue fieldValue : fieldValues) {
            final IndexField indexField = fieldValue.field();
            if (indexField.isIndexed()) {
                final Analyzer fieldAnalyzer = searchExpressionQueryCache.getAnalyser(indexField);
                final IndexableField field = FieldFactory.create(fieldValue);
                TokenStream tokenStream = field.tokenStream(fieldAnalyzer, null);
                if (tokenStream != null) {
                    memoryIndex.addField(field.name(), tokenStream, field.boost());
                }
            }

            searchExpressionQueryCache.addIndexField(indexField);
        }

        // See if this set of fields matches the rule expression.
        return matchQuery(searchRequest, memoryIndex);
    }

    private boolean matchQuery(final SearchRequest searchRequest, final MemoryIndex memoryIndex) {
        try {
            final SearchExpressionQuery query = searchExpressionQueryCache.getQuery(searchRequest, true);
            final IndexSearcher indexSearcher = memoryIndex.createSearcher();
            final TopDocs docs = indexSearcher.search(query.getQuery(), 100);

            if (docs.totalHits == 0) {
                return false;
            } else if (docs.totalHits == 1) {
                return true;
            } else {
                LOGGER.error("Unexpected number of documents {}  found by rule, should be 1 or 0.", docs.totalHits);
            }
        } catch (final SearchException | IOException se) {
            LOGGER.warn("Unable to create alerts for rule " + searchRequest.getQuery() + " due to " + se.getMessage());
        }

        return false;
    }
}
