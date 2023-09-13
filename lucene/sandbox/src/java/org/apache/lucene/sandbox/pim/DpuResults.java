package org.apache.lucene.sandbox.pim;

import java.io.IOException;
import org.apache.lucene.search.LeafSimScorer;

/**
 * Class used to read results coming from PIM and score them on the fly Read the results directly
 * from the byte array filled by the PIM communication APIs, without copying
 */
public class DpuResults {

  private PimQuery query;
  private DpuResultsReader reader;
  private LeafSimScorer simScorer;
  private PimMatch match;

  DpuResults(PimQuery query, DpuResultsReader reader, LeafSimScorer simScorer) {
    this.query = query;
    this.reader = reader;
    this.simScorer = simScorer;
    this.match = new PimMatch(-1, 0.0F);
  }

  public boolean next() {
    if (reader.eof()) return false;
    try {
      query.readResult(reader, simScorer, match);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return true;
  }

  public PimMatch match() {
    return match;
  }
}
