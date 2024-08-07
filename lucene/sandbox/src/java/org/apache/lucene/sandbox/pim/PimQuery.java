/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.sandbox.pim;

import java.io.IOException;
import org.apache.lucene.search.LeafSimScorer;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.DataOutput;

/** Interface to be implemented by all PIM queries */
public interface PimQuery {

  /**
   * Write this query to PIM This defines the format of the query to be sent to the PIM system
   *
   * @param output the output to be written
   * @throws IOException if the query failed to be written
   */
  void writeToPim(DataOutput output) throws IOException;

  /**
   * This API specifies the size in bytes of a result for this PIM query
   *
   * @return the number of bytes returned by the PIM system for a query result
   */
  int getResultByteSize();

  /**
   * @return the field associated to this query
   */
  String getField();

  /**
   * @return the maximum number of hits that are needed from the DPU system
   */
  default int getMaxNumHitsFromPimSystem() {
    return Integer.MAX_VALUE;
  }
}
