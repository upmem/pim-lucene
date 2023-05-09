package org.apache.lucene.sandbox.pim;

import org.apache.lucene.index.IndexReaderContext;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermStates;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TermStatistics;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.util.ArrayUtil;

import java.io.IOException;

/**
 * PIM {@link PhraseQuery}.
 * <p>
 * Supports only {@link BM25Similarity}. If another similarity is required by the
 * {@link IndexSearcher}, then this query is rewritten to a regular {@link PhraseQuery}.
 */
public class PimPhraseQuery extends PhraseQuery {

  public static class Builder extends PhraseQuery.Builder {

    @Override
    public PimPhraseQuery build() {
      PhraseQuery query = super.build();
      return new PimPhraseQuery(query.getSlop(), query.getTerms(), query.getPositions());
    }
  }

  public PimPhraseQuery(String field, String... terms) {
    super(field, terms);
  }

  public PimPhraseQuery(int slop, String field, String... terms) {
    super(slop, field, terms);
  }

  private PimPhraseQuery(int slop, Term[] terms, int[] positions) {
    super(slop, terms, positions);
  }

  @Override
  public Query rewrite(IndexSearcher searcher) throws IOException {
    Query query = super.rewrite(searcher);
    if (query instanceof PhraseQuery pq) {
      if (!(searcher.getSimilarity() instanceof BM25Similarity)) {
        PhraseQuery.Builder builder = new PhraseQuery.Builder()
          .setSlop(pq.getSlop());
        for (int i = 0; i < pq.getTerms().length; i++) {
          builder.add(pq.getTerms()[i], pq.getPositions()[i]);
        }
        query = builder.build();
      } else if (query != this) {
        query = new PimPhraseQuery(pq.getSlop(), pq.getTerms(), pq.getPositions());
      }
    }
    return query;
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
    throws IOException {
    if (getPositions().length < 2) {
      throw new IllegalStateException(
        "PhraseWeight does not support less than 2 terms, call rewrite first");
    } else if (getPositions()[0] != 0) {
      throw new IllegalStateException(
        "PhraseWeight requires that the first position is 0, call rewrite first");
    } else if (!(searcher.getSimilarity() instanceof BM25Similarity)) {
      throw new IllegalStateException(
        getClass().getSimpleName() + " supports only " + BM25Similarity.class.getSimpleName()
          + ", call rewrite first");
    }
    PimPhraseScoreStats scoreStats = buildScoreStats(searcher, scoreMode, boost);
    return scoreStats == null ? noMatchWeight()
      : new PimPhraseWeight(this, scoreStats);
  }

  private PimPhraseScoreStats buildScoreStats(IndexSearcher searcher, ScoreMode scoreMode, float boost)
    throws IOException {
    IndexReaderContext context = searcher.getTopReaderContext();
    TermStatistics[] termStats = new TermStatistics[getTerms().length];
    int termUpTo = 0;
    for (final Term term : getTerms()) {
      if (scoreMode.needsScores()) {
        TermStates ts = TermStates.build(context, term, true);
        if (ts.docFreq() > 0) {
          termStats[termUpTo++] =
            searcher.termStatistics(term, ts.docFreq(), ts.totalTermFreq());
        }
      }
    }
    if (termUpTo == 0) {
      return null; // No terms at all, no score.
    }
    return new PimPhraseScoreStats(
      searcher,
      searcher.getSimilarity(),
      scoreMode,
      boost,
      searcher.collectionStatistics(getField()),
      ArrayUtil.copyOfSubArray(termStats, 0, termUpTo));
  }

  private Weight noMatchWeight() {
    return new ConstantScoreWeight(this, 0) {
      @Override
      public Scorer scorer(LeafReaderContext leafReaderContext) {
        return null;
      }

      @Override
      public boolean isCacheable(LeafReaderContext ctx) {
        return true;
      }
    };
  }
}