package org.apache.lucene.sandbox.pim;

import com.upmem.dpu.DpuException;
import com.upmem.dpu.DpuSet;
import com.upmem.dpu.DpuSystem;
import org.apache.lucene.util.BitUtil;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

class DpuSystemExecutor implements PimQueriesExecutor {
    private final DpuSystem dpuSystem;
    private final ByteArrayOutputStream dpuStream;
    private final byte[][] dpuQueryResultsAddr;
    private final byte[][] dpuResults;
    private final byte[][][] dpuResultsPerRank;
    private final int[] dpuIdOffset;

    DpuSystemExecutor() throws DpuException {
        // allocate DPUs, load the program, allocate space for DPU results
        dpuStream = new ByteArrayOutputStream();
        dpuSystem = DpuSystem.allocate(DpuConstants.nrDpus, "sgXferEnable=true", new PrintStream(dpuStream));
        dpuSystem.load(DpuConstants.dpuProgramPath);
        dpuQueryResultsAddr = new byte[dpuSystem.dpus().size()][DpuConstants.dpuQueryBatchByteSize];
        dpuResults = new byte[dpuSystem.dpus().size()][DpuConstants.dpuResultsMaxByteSize];
        dpuResultsPerRank = new byte[dpuSystem.ranks().size()][][];
        dpuIdOffset = new int[dpuSystem.dpus().size()];
        int cnt = 0;
        for (int i = 0; i < dpuSystem.ranks().size(); ++i) {
            dpuResultsPerRank[i] = new byte[dpuSystem.ranks().get(i).dpus().size()][];
            dpuIdOffset[i] = cnt;
            for (int j = 0; j < dpuSystem.ranks().get(i).dpus().size(); ++j) {
                dpuResultsPerRank[i][j] = dpuResults[cnt++];
            }
        }
    }

    @Override
    public void setPimIndex(PimIndexInfo pimIndexInfo) {
        // TODO copy the PIM index in each DPU
    }

    public void executeQueries(ByteBufferBoundedQueue.ByteBuffers queryBatch, PimSystemManager.ResultReceiver resultReceiver)
            throws DpuException {

        // 1) send queries to PIM
        sendQueriesToPIM(queryBatch);

        // 2) launch DPUs (program should be loaded on PimSystemManager Index load (only once)
        dpuSystem.async().exec();

        // 3) results transfer from DPUs to CPU
        // first get the meta-data (index of query results in results array for each DPU)
        // This meta-data has one integer per query in the batch
        dpuSystem.async().copy(dpuQueryResultsAddr, DpuConstants.dpuResultsIndexVarName,
                queryBatch.getNbElems() * Integer.BYTES);

        // then transfer the results
        // use a callback to transfer a minimal number of results per rank
        final int batchSize = queryBatch.getNbElems() * Integer.BYTES;
        dpuSystem.async().call(
                (DpuSet set, int rankId) -> {
                    // find the max byte size of results for DPUs in this rank
                    int resultsSize = 0;
                    for (int i = 0; i < set.dpus().size(); ++i) {
                        int dpuResultsSize = (int) BitUtil.VH_LE_INT.get(
                                dpuQueryResultsAddr[dpuIdOffset[rankId] + i], batchSize);
                        if (dpuResultsSize > resultsSize)
                            resultsSize = dpuResultsSize;
                    }
                    // perform the transfer for this rank
                    set.copy(dpuResultsPerRank[rankId], DpuConstants.dpuResultsBatchVarName, resultsSize);
                }
        );

        // 4) barrier to wait for all transfers to be finished
        dpuSystem.async().sync();

        // 5) Update the results map for the client threads to read their results
        resultReceiver.startResultBatch();
        try {
            for (int q = 0; q < queryBatch.getNbElems(); ++q) {
                resultReceiver.addResult(queryBatch.getUniqueIdOf(q),
                        new DpuResultsInput(dpuResults, dpuQueryResultsAddr, q));
            }
        } finally {
            resultReceiver.endResultBatch();
        }

    }

    private void sendQueriesToPIM(ByteBufferBoundedQueue.ByteBuffers queryBatch) throws DpuException {

        // if the query is too big for the limit on DPU, throw an exception
        // The query would have to be handled by the CPU
        if (queryBatch.getSize() > DpuConstants.dpuQueryBatchByteSize)
            throw new DpuException("Query too big: size=" + queryBatch.getSize() + " limit=" + DpuConstants.dpuQueryBatchByteSize);

        // there is a special case when the byte buffer slice spans ends and beginning of the byte buffer
        if (queryBatch.isSplitted()) {
            int firstSliceNbElems = queryBatch.getBuffer().length - queryBatch.getStartIndex();
            int secondSliceNbElems = queryBatch.getSize() - firstSliceNbElems;
            dpuSystem.async().copy(DpuConstants.dpuQueryBatchVarName, queryBatch.getBuffer(), queryBatch.getStartIndex(),
                    firstSliceNbElems, 0);
            dpuSystem.async().copy(DpuConstants.dpuQueryBatchVarName, queryBatch.getBuffer(), 0,
                    secondSliceNbElems, firstSliceNbElems);
        } else {
            dpuSystem.async().copy(DpuConstants.dpuQueryBatchVarName, queryBatch.getBuffer(), queryBatch.getStartIndex(),
                    queryBatch.getSize(), 0);
        }
    }

    @Override
    public void executeQueries(List<PimSystemManager2.QueryBuffer> queryBuffers, PimSystemManager.ResultReceiver resultReceiver) {
        //TODO
    }
}
