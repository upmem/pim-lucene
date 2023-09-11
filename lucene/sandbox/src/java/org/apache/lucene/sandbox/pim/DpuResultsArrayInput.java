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
import org.apache.lucene.store.ByteArrayDataInput;

/** Read the DPU results using a ByteArrayDataInput (used for the software simulator) */
public class DpuResultsArrayInput extends DpuResultsReader {

  private ByteArrayDataInput in;

  DpuResultsArrayInput(ByteArrayDataInput in) {
    this.in = in;
  }

  @Override
  public byte readByte() throws IOException {
    return in.readByte();
  }

  @Override
  public void readBytes(byte[] b, int offset, int len) throws IOException {
    in.readBytes(b, offset, len);
  }

  @Override
  public void skipBytes(long numBytes) throws IOException {
    in.skipBytes(numBytes);
  }

  @Override
  public boolean eof() {
    return in.eof();
  }
}
