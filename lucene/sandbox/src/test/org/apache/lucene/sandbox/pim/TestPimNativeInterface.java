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

import org.apache.lucene.sandbox.sdk.DpuException;
import org.apache.lucene.sandbox.sdk.DpuSystem;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.junit.BeforeClass;

public class TestPimNativeInterface extends LuceneTestCase {
  @BeforeClass
  public static void beforeClass() {
    System.loadLibrary("dpuLucene");
  }

  public static native int getNrOfDpus(DpuSystem dpuSystem);

  public void testReadDpuSystemFromJNI() {
    DpuSystem dpuSystem;
    ByteArrayOutputStream dpuStream = new ByteArrayOutputStream();
    try {
      dpuSystem = DpuSystem.allocate(4, "", new PrintStream(dpuStream, true, "UTF-8"));
    } catch (UnsupportedEncodingException | DpuException e) {
      throw new RuntimeException(e);
    }

    int nrOfDpus = getNrOfDpus(dpuSystem);
    assertEquals(4, nrOfDpus);

    try{
      dpuSystem.close();
    }
    catch (DpuException e) {
      throw new RuntimeException(e);
    }
  }
}
