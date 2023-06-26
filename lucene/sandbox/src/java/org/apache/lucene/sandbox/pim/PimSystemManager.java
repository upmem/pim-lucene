package org.apache.lucene.sandbox.pim;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.LeafSimScorer;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.DataInput;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.upmem.dpu.DpuException;

/**
 * PimSystemManager
 * Singleton class used to manage the PIM system and offload Lucene queries to it.
 * TODO currently this uses a software model to answer queries, not the PIM HW.
 */
public final class PimSystemManager {

    private static class SingletonHolder {
        static final PimSystemManager INSTANCE = new PimSystemManager();
    }

    private static final int BYTE_BUFFER_QUEUE_LOG2_BYTE_SIZE = 11;
    private static final int QUERY_BATCH_SIZE = 128;
    private static final boolean DEBUG = false;
    private static final boolean USE_SOFTWARE_MODEL = true;

    private boolean isIndexLoaded;
    private boolean isIndexBeingLoaded;
    private PimIndexInfo pimIndexInfo;
    private final ByteBufferBoundedQueue queryBuffer;

    // for the moment, the PIM index search is performed on CPU
    // using this class, no PIM HW involved

    private final Lock queryLock = new ReentrantLock();
    private final Condition queryPushedCond  = queryLock.newCondition();
    private final ReentrantReadWriteLock resultsLock = new ReentrantReadWriteLock();
    private final Lock resultsPushedLock = new ReentrantLock();
    private final Condition resultsPushedCond = resultsPushedLock.newCondition();

    private final PimQueriesExecutor queriesExecutor;
    private final TreeMap<Integer, DataInput> queryResultsMap;
    private final ResultReceiver resultReceiver;
    private final QueryRunner queryRunner;

    private PimSystemManager() {
        isIndexLoaded = false;
        isIndexBeingLoaded = false;
        pimIndexInfo = null;
        try {
            queryBuffer = new ByteBufferBoundedQueue(BYTE_BUFFER_QUEUE_LOG2_BYTE_SIZE);
        } catch (ByteBufferBoundedQueue.BufferLog2SizeTooLargeException e) {
            throw new RuntimeException(e);
        }
        queryResultsMap = new TreeMap<>();
        resultReceiver = new ResultReceiver();
        queryRunner = new QueryRunner();
        Thread t = new Thread(queryRunner, getClass().getSimpleName() + "-" + queryRunner.getClass().getSimpleName());
        t.setDaemon(true);
        t.start();


        if (USE_SOFTWARE_MODEL) {
            queriesExecutor = new DpuSystemSimulator();
        } else {
            try {
                queriesExecutor = new DpuSystemExecutor();
            } catch (DpuException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Singleton accessor
     *
     * @return unique PimSystemManager instance
     */
    public static PimSystemManager get() {
        return SingletonHolder.INSTANCE;
    }

    /**
     * Load the pim index unless one is already loaded
     *
     * @param pimDirectory the directory containing the PIM index
     * @return true if the index was successfully loaded
     */
    public boolean loadPimIndex(Directory pimDirectory) throws IOException {

        if (!isIndexLoaded && !isIndexBeingLoaded) {
            boolean loadSuccess = false;
            //synchronized block for thread safety
            synchronized (PimSystemManager.class) {
                if (!isIndexLoaded && !isIndexBeingLoaded) {
                    getPimInfoFromDir(pimDirectory);
                    isIndexBeingLoaded = true;
                    loadSuccess = true;
                }
            }
            if (loadSuccess) {
                // the calling thread has succeeded loading the PIM Index
                transferPimIndex();
                synchronized (PimSystemManager.class) {
                    isIndexBeingLoaded = false;
                    isIndexLoaded = true;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Load the pim index and can force load if an index is already loaded
     *
     * @param pimDirectory the directory containing the PIM index
     * @param force when true, unload the currently loaded index to force load the new one
     * @return true if the index was successfully loaded
     */
    public boolean loadPimIndex(Directory pimDirectory, boolean force) throws IOException {

        if(force)
            unloadPimIndex();
        return loadPimIndex(pimDirectory);
    }

    /**
     * Unload the PIM index if currently loaded
     *
     * @return true if the index has been unloaded
     */
    public boolean unloadPimIndex() {

        if (isIndexLoaded) {
            //synchronized block for thread safety
            synchronized (PimSystemManager.class) {
                if (isIndexLoaded) {
                    // set the boolean variable to false,
                    // authorizing for a new load that will
                    // overwrite current PIM Index
                    isIndexLoaded = false;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return true if an index is currently loaded in the PIM system
     */
    public boolean isIndexLoaded() {
        synchronized (PimSystemManager.class) {
            return isIndexLoaded;
        }
    }

    /**
     * @return number of dpus used by the index if an index is currently loaded
     * in the PIM system and zero otherwise
     */
    public int getNbDpus() {
        synchronized (PimSystemManager.class) {
            if(isIndexLoaded) {
                return pimIndexInfo.getNumDpus();
            }
        }
        return 0;
    }

    /**
     * NOT IMPLEMENTED
     * Tells whether the current PIM index loaded is
     * the right one to answer queries for the LeafReaderContext object
     * TODO implement this, returns always true
     *
     * @param context
     * @return
     */
    public boolean isReady(LeafReaderContext context) {
        //TODO check if the PIM system has the correct index loaded
        //need to find a way to correlate this context with the PimIndexInfo
        return isIndexLoaded;
    }

    /**
     * Information on which query types are supported by the PIM system
     *
     * @param query the input query
     * @return true if the query is supported by the PIM system
     */
    public boolean isQuerySupported(Query query) {
        // for the moment support only PimPhraseQuery
        if (query instanceof PimPhraseQuery)
            return true;
        else
            return false;
    }

    /**
     * Kill the thread created by this singleton to
     * handle the interface with the PIM HW
     */
    public static void shutDown() {
        PimSystemManager manager = get();
        manager.queryRunner.stop();
        manager.queryLock.lock();
        try {
            manager.queryPushedCond.signal();
        } finally {
            manager.queryLock.unlock();
        }
    }

    /** Custom Exception to be thrown when the PimSystemManager query queue is full */
    public static final class PimQueryQueueFullException extends Exception {

        public PimQueryQueueFullException() {
            super(
                    "PimSystemManager query queue is full");
        }
    }

    /**
     * Queries are sent in batches to the PIM system
     * This call will push the query into a submit queue, and wait
     * for the results to be available
     * <p>
     * It is the responsibility of the caller to make sure that an
     * index was previously successfully loaded with a call to loadPimIndex
     * returning true, and that no unloadPimIndex method was called
     *
     * @param context the leafReaderContext to search
     * @param query   the query to execute
     * @param scorer  the scorer to use to score the results
     * @return the list of matches
     */
    public <QueryType extends Query & PimQuery>  List<PimMatch> search(LeafReaderContext context,
                                              QueryType query, LeafSimScorer scorer) throws PimQueryQueueFullException {

        if (!isQuerySupported(query))
            return null;

        try {
            // 1) push query in a queue
            // first request a buffer of the correct size
            ByteCountDataOutput countOutput = new ByteCountDataOutput();
            writeQueryToPim(countOutput, query, context.ord);
            final int byteSize = Math.toIntExact(countOutput.getByteCount());
            var queryOutput = queryBuffer.add(byteSize);
            // unique id identifying the query in the queue
            int id = queryOutput.getUniqueId();

            // write leaf id, query type, then write query
            writeQueryToPim(queryOutput, query, context.ord);

            // 2) signal condition variable to wake up thread which handle queries to DPUs
            queryLock.lock();
            try {
                if (DEBUG)
                    System.out.println("Signal query");
                queryPushedCond.signal();
            } finally {
                queryLock.unlock();
            }
            if (DEBUG)
                System.out.println("Waiting for result");

            // 3) wait on condition variable until new results were collected
            // 4) check if the result is present, if not wait again on condition variable
            resultsPushedLock.lock();
            try {
                while (!queryResultsAvailable(id))
                    resultsPushedCond.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                resultsPushedLock.unlock();
            }

            if (DEBUG)
                System.out.println("Reading result");

            // results are available
            return getQueryMatches(query, id, scorer);

        } catch (ByteBufferBoundedQueue.InsufficientSpaceInQueueException e) {
            // not enough capacity in queue
            // throw an exception that the user should catch, and
            // issue the query later or use the CPU instead of PIM system
            throw new PimQueryQueueFullException();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Check if the results of a query are ready (i.e., returned by the PIM system)
     * @param id the id of the query
     * @return true if the results are present
     */
    private boolean queryResultsAvailable(int id) {

        resultsLock.readLock().lock();
        try {
            return queryResultsMap.get(id) != null;
        }
        finally {
            resultsLock.readLock().unlock();
        }
    }

    /**
     * Returns the results of a query by interpreting data returned by the PIM system and
     * creating a list of PimMatch objects.
     * @param q the query
     * @param id the id of the query (specific to this PimManager)
     * @param scorer the scorer to be used to score results obtained from the PIM system
     * @return the list of matches for this query
     * @param <QueryType> a type that is both a Query and a PimQuery
     * @throws IOException
     */
    private <QueryType extends Query & PimQuery> List<PimMatch> getQueryMatches(
            QueryType q, int id, LeafSimScorer scorer) throws IOException {

        resultsLock.readLock().lock();
        DataInput resultsReader;
        try {
            resultsReader = queryResultsMap.get(id);
        }
        finally {
            resultsLock.readLock().unlock();
        }
        assert resultsReader != null;

        List<PimMatch> matches = getMatches(q, resultsReader, scorer);

        // remove results array from the map
        resultsLock.writeLock().lock();
        try {
            queryResultsMap.remove(id);
        }
        finally {
            resultsLock.writeLock().unlock();
        }
        return matches;
    }

    /**
     * Used by method getQueryMatches
     */
    private <QueryType extends Query & PimQuery> List<PimMatch> getMatches(
            QueryType q, DataInput input, LeafSimScorer scorer) throws IOException {

        List<PimMatch> matches = new ArrayList<>();

        // 1) read number of results
        int nbResults = input.readVInt();

        // 2) loop and call readResult (specialized on query type, return a PimMatch)
        for(int i = 0; i < nbResults; ++i) {
            PimMatch m = q.readResult(input, scorer);
            if(m != null)
                matches.add(m);
        }
        return matches;
    }

    /**
     * Write the query as a byte array in the PIM system format
     * @param output the output where to write the query
     * @param query the query to be written
     * @param leafIdx the leaf id
     * @param <QueryType> a type that is both a Query and a PimQuery
     * @throws IOException
     */
    private <QueryType extends Query & PimQuery>
    void writeQueryToPim(DataOutput output, QueryType query, int leafIdx) throws IOException {

        output.writeVInt(leafIdx);
        output.writeByte(DpuConstants.PIM_PHRASE_QUERY_TYPE);
        query.writeToPim(output);
    }

    /**
     * Copy the PIM index to the PIM system
     */
    private void transferPimIndex() {
        // TODO load index to PIM system
        // Lock the pim index to avoid it to be overwritten ?
        queriesExecutor.setPimIndex(pimIndexInfo);
    }

    /**
     * Read from the PIM directory the information about the PIM index
     * @param pimDirectory the directory containing the PIM index
     * @throws IOException
     */
    private void getPimInfoFromDir(Directory pimDirectory) throws IOException {

        IndexInput infoInput = pimDirectory.openInput("pimIndexInfo", IOContext.DEFAULT);
        byte[] bytes = new byte[(int) infoInput.length()];
        infoInput.readBytes(bytes, 0, bytes.length);
        infoInput.close();
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        ObjectInputStream objectInputStream
                = new ObjectInputStream(bais);
        try {
            pimIndexInfo = (PimIndexInfo) objectInputStream.readObject();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        objectInputStream.close();
        pimIndexInfo.setPimDir(pimDirectory);
    }

    /**
     * PIM Manager thread
     * This thread is responsible for regularly checking the query input queue, executing a batch of queries,
     * and pushing the results in a TreeMap.
     */
    private class QueryRunner implements Runnable {

        static final int NUM_QUERIES_MIN = 8;
        static final int WAIT_FOR_BATCH_NS = 0;
        volatile boolean running = true;
        //static final int waitForBatchNanoTime = 10000;

        public void stop() {
            running = false;
        }

        @Override
        public void run() {
            while (running) {

                try {
                    // wait for a query to be pushed
                    ByteBufferBoundedQueue.ByteBuffers slice = queryBuffer.peekMany(QUERY_BATCH_SIZE);
                    while (slice.getNbElems() == 0) {
                        queryBuffer.release();
                        // found no query, need to wait for one
                        // take the lock, verify that still no query and wait on condition variable
                        queryLock.lock();
                        try {
                            slice = queryBuffer.peekMany(QUERY_BATCH_SIZE);
                            if(slice.getNbElems() == 0) {
                                queryBuffer.release();
                                if(DEBUG)
                                    System.out.println("Waiting for query");
                                queryPushedCond.await();
                                if (!running) {
                                    return;
                                }
                            }
                        } finally {
                            queryLock.unlock();
                        }
                        if(slice.getNbElems() == 0) {
                            queryBuffer.release();
                            slice = queryBuffer.peekMany(QUERY_BATCH_SIZE);
                        }
                    }

                    // if the number of queries is under a minimum threshold, wait a bit more
                    // to give a second chance to accumulate more queries and send a larger batch to DPUs
                    // this is a throughput oriented strategy
                    if (WAIT_FOR_BATCH_NS != 0 && slice.getNbElems() < NUM_QUERIES_MIN) {
                        queryBuffer.release();
                        if(DEBUG)
                            System.out.println("Handling query but waiting a bit more");
                        Thread.sleep(0, WAIT_FOR_BATCH_NS);
                        slice = queryBuffer.peekMany(QUERY_BATCH_SIZE);
                    }
                    if(DEBUG)
                        System.out.println("Handling query");

                    // send the query batch to the DPUs, launch, get results
                    queriesExecutor.executeQueries(slice, resultReceiver);
                    resultsPushedLock.lock();
                    try {
                        // signal client threads that some results are available
                        if(DEBUG)
                            System.out.println("Signal results, nb res:" + queryResultsMap.size());
                        resultsPushedCond.signalAll();
                    } finally {
                        resultsPushedLock.unlock();
                    }

                    // remove the slice handled
                    queryBuffer.remove();
                } catch (InterruptedException e) {
                    // interrupted, return
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } catch (ByteBufferBoundedQueue.ParallelPeekException e) {
                    throw new RuntimeException(e);
                } catch (DpuException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    interface PimQueriesExecutor {

        void setPimIndex(PimIndexInfo pimIndexInfo);

        void executeQueries(ByteBufferBoundedQueue.ByteBuffers queryBatch, ResultReceiver resultReceiver)
                throws IOException, DpuException;
    }

    class ResultReceiver {

        void startResultBatch() {
            resultsLock.writeLock().lock();
        }

        void addResult(Integer resultId, DataInput result) {
            queryResultsMap.put(resultId, result);
        }

        void endResultBatch() {
            resultsLock.writeLock().unlock();
        }
    }

}
