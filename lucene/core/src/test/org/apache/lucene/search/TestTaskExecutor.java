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
package org.apache.lucene.search;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.NamedThreadFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;

public class TestTaskExecutor extends LuceneTestCase {

  private static ExecutorService executorService;

  @BeforeClass
  public static void createExecutor() {
    executorService =
        Executors.newFixedThreadPool(
            1, new NamedThreadFactory(TestTaskExecutor.class.getSimpleName()));
  }

  @AfterClass
  public static void shutdownExecutor() {
    executorService.shutdown();
  }

  public void testUnwrapIOExceptionFromExecutionException() {
    TaskExecutor taskExecutor = new TaskExecutor(executorService);
    TaskExecutor.Task<?> task =
        taskExecutor.createTask(
            () -> {
              throw new IOException("io exception");
            });
    IOException ioException =
        expectThrows(
            IOException.class, () -> taskExecutor.invokeAll(Collections.singletonList(task)));
    assertEquals("io exception", ioException.getMessage());
  }

  public void testUnwrapRuntimeExceptionFromExecutionException() {
    TaskExecutor taskExecutor = new TaskExecutor(executorService);
    TaskExecutor.Task<?> task =
        taskExecutor.createTask(
            () -> {
              throw new RuntimeException("runtime");
            });
    RuntimeException runtimeException =
        expectThrows(
            RuntimeException.class, () -> taskExecutor.invokeAll(Collections.singletonList(task)));
    assertEquals("runtime", runtimeException.getMessage());
    assertNull(runtimeException.getCause());
  }

  public void testUnwrapErrorFromExecutionException() {
    TaskExecutor taskExecutor = new TaskExecutor(executorService);
    TaskExecutor.Task<?> task =
        taskExecutor.createTask(
            () -> {
              throw new OutOfMemoryError("oom");
            });
    OutOfMemoryError outOfMemoryError =
        expectThrows(
            OutOfMemoryError.class, () -> taskExecutor.invokeAll(Collections.singletonList(task)));
    assertEquals("oom", outOfMemoryError.getMessage());
    assertNull(outOfMemoryError.getCause());
  }

  public void testUnwrappedExceptions() {
    TaskExecutor taskExecutor = new TaskExecutor(executorService);
    TaskExecutor.Task<?> task =
        taskExecutor.createTask(
            () -> {
              throw new Exception("exc");
            });
    RuntimeException runtimeException =
        expectThrows(
            RuntimeException.class, () -> taskExecutor.invokeAll(Collections.singletonList(task)));
    assertEquals("exc", runtimeException.getCause().getMessage());
  }

  public void testInvokeAllFromTaskDoesNotDeadlockSameSearcher() throws IOException {
    try (Directory dir = newDirectory();
        RandomIndexWriter iw = new RandomIndexWriter(random(), dir)) {
      for (int i = 0; i < 500; i++) {
        iw.addDocument(new Document());
      }
      try (DirectoryReader reader = iw.getReader()) {
        IndexSearcher searcher =
            new IndexSearcher(reader, executorService) {
              @Override
              protected LeafSlice[] slices(List<LeafReaderContext> leaves) {
                return slices(leaves, 1, 1);
              }
            };

        searcher.search(
            new MatchAllDocsQuery(),
            new CollectorManager<Collector, Void>() {
              @Override
              public Collector newCollector() {
                return new Collector() {
                  @Override
                  public LeafCollector getLeafCollector(LeafReaderContext context) {
                    return new LeafCollector() {
                      @Override
                      public void setScorer(Scorable scorer) throws IOException {
                        TaskExecutor.Task<Void> task =
                            searcher
                                .getTaskExecutor()
                                .createTask(
                                    () -> {
                                      // make sure that we don't miss disabling concurrency one
                                      // level deeper
                                      TaskExecutor.Task<Object> anotherTask =
                                          searcher.getTaskExecutor().createTask(() -> null);
                                      searcher
                                          .getTaskExecutor()
                                          .invokeAll(Collections.singletonList(anotherTask));
                                      return null;
                                    });
                        searcher.getTaskExecutor().invokeAll(Collections.singletonList(task));
                      }

                      @Override
                      public void collect(int doc) {}
                    };
                  }

                  @Override
                  public ScoreMode scoreMode() {
                    return ScoreMode.COMPLETE;
                  }
                };
              }

              @Override
              public Void reduce(Collection<Collector> collectors) {
                return null;
              }
            });
      }
    }
  }

  public void testInvokeAllFromTaskDoesNotDeadlockMultipleSearchers() throws IOException {
    try (Directory dir = newDirectory();
        RandomIndexWriter iw = new RandomIndexWriter(random(), dir)) {
      for (int i = 0; i < 500; i++) {
        iw.addDocument(new Document());
      }
      try (DirectoryReader reader = iw.getReader()) {
        IndexSearcher searcher =
            new IndexSearcher(reader, executorService) {
              @Override
              protected LeafSlice[] slices(List<LeafReaderContext> leaves) {
                return slices(leaves, 1, 1);
              }
            };

        searcher.search(
            new MatchAllDocsQuery(),
            new CollectorManager<Collector, Void>() {
              @Override
              public Collector newCollector() {
                return new Collector() {
                  @Override
                  public LeafCollector getLeafCollector(LeafReaderContext context) {
                    return new LeafCollector() {
                      @Override
                      public void setScorer(Scorable scorer) throws IOException {
                        // the thread local used to prevent deadlock is static, so while each
                        // searcher has its own
                        // TaskExecutor, the safeguard is shared among all the searchers that get
                        // the same executor
                        IndexSearcher indexSearcher = new IndexSearcher(reader, executorService);
                        TaskExecutor.Task<Void> task =
                            indexSearcher.getTaskExecutor().createTask(() -> null);
                        searcher.getTaskExecutor().invokeAll(Collections.singletonList(task));
                      }

                      @Override
                      public void collect(int doc) {}
                    };
                  }

                  @Override
                  public ScoreMode scoreMode() {
                    return ScoreMode.COMPLETE;
                  }
                };
              }

              @Override
              public Void reduce(Collection<Collector> collectors) {
                return null;
              }
            });
      }
    }
  }
}
