#include <stdint.h>
#include <defs.h>
#include <mram.h>
#include <barrier.h>
#include <mutex.h>
#include <assert.h>
#include <alloc.h>
#include <string.h>
#include <mram_unaligned.h>
#include "matcher.h"
#include "decoder.h"
#include "common.h"

/**
  Input queries
  */
__host uint32_t nb_queries_in_batch;
__host uint32_t nb_bytes_in_batch;
__mram_noinit uint8_t query_batch[DPU_QUERY_BATCH_BYTE_SIZE];
__host uint32_t query_offset_in_batch[DPU_MAX_BATCH_SIZE];

/**
  Output results
  */
__mram_noinit uint8_t results_batch[DPU_RESULTS_MAX_BYTE_SIZE];
__mram_noinit uint8_t results_index[DPU_MAX_BATCH_SIZE];

uint32_t batch_num = 0;
MUTEX_INIT(batch_mutex);
BARRIER_INIT(barrier, NR_TASKLETS);

#ifdef TEST1
#define TEST
#include "../test/test1.h"
#include <stdio.h>
#endif

static void perform_did_and_pos_matching(uint32_t query_id, did_matcher_t *matchers, uint32_t nr_terms);

int main() {

    if(me() == 0) {
        mem_reset();
        batch_num = 0;
        initialize_decoder_pool();
#ifdef TEST
        // in test mode set the queries inputs correctly
        nb_queries_in_batch = test_nb_queries_in_batch;
        nb_bytes_in_batch = test_nb_bytes_in_batch;
        mram_write(test_query_batch, query_batch, ((test_nb_bytes_in_batch + 7) >> 3) << 3);
        memcpy(query_offset_in_batch, test_query_offset_in_batch, test_nb_queries_in_batch * sizeof(uint32_t));
#endif
    }
    barrier_wait(&barrier);

    // each tasklet loops and take the next query in the batch, until no more queries
    uint32_t batch_num_tasklet;
    while(1) {
        mutex_lock(batch_mutex);
        batch_num_tasklet = batch_num++;
        mutex_unlock(batch_mutex);
        if(batch_num_tasklet >= nb_queries_in_batch)
            break;

        // initialize a query parser
        query_parser_t query_parser;
        init_query_parser(&query_parser, query_batch + query_offset_in_batch[batch_num_tasklet]);

        // read segment id and query type
        uint32_t segment_id;
        uint8_t query_type;
        read_segment_id(&query_parser, &segment_id);
        read_query_type(&query_parser, &query_type);
        assert(query_type == PIM_PHRASE_QUERY_TYPE); // only PIM PHRASE QUERY TYPE supported

#ifdef TEST
        did_matcher_t *matchers = setup_matchers(&query_parser, (uintptr_t)(&index_mram[0]));
#else
        did_matcher_t *matchers = setup_matchers(&query_parser, (uintptr_t)DPU_MRAM_HEAP_POINTER);
#endif

#ifdef TEST
        printf("Query %d: %d terms\n", batch_num_tasklet, query_parser.nr_terms);
#endif
        // a null matchers means one of the term of the query is not present in the index and we can skip it
        if(matchers != 0)
            perform_did_and_pos_matching(batch_num_tasklet, matchers, query_parser.nr_terms);

        release_matchers(matchers, query_parser.nr_terms);
    }

    return 0;
}

static void perform_pos_matching_for_did(uint32_t query_id, did_matcher_t *matchers,
                                                unsigned int nr_terms, uint32_t did)
{
    start_pos_matching(matchers, nr_terms);

    if (!matchers_has_next_pos(matchers, nr_terms))
        goto end;

    while (true) {
        uint32_t max_pos, index;

        get_max_pos_and_index(matchers, nr_terms, &index, &max_pos);

        switch (seek_pos(matchers, nr_terms, max_pos, index)) {
        case POSITIONS_FOUND: {
            /*uint32_t nresults;
            mutex_lock(mutex_responses);
            nresults = DPU_STATS_VAR[each_request].nb_results++;
            mutex_unlock(mutex_responses);
            if (nresults < MAX_RESPONSES) {
                mutex_lock(mutex_responses_2);
                if (nr_total_responses < MAX_RESPONSES * nr_request_in_batch_input) {
                    uint32_t response_id = nr_total_responses++;
                    mutex_unlock(mutex_responses_2);
                    __dma_aligned response_t response = { .did = did, .pos = max_pos - index, .req = each_request };
                    mram_write(&response, &DPU_RESPONSES_VAR[response_id], sizeof(response));
                    UPDATE_BYTES_WRITTEN;
                } else
                    mutex_unlock(mutex_responses_2);
            }
            */
            //TODO store the result in the results buffer
#ifdef TEST
            printf("Found a result for query %d: did=%d, pos=%d\n", query_id, did, max_pos - index);
#endif
            goto end;
        }
        case POSITIONS_NOT_FOUND:
            break;
        case END_OF_POSITIONS:
            goto end;
        }
    }
end:
    stop_pos_matching(matchers, nr_terms);
}

static void perform_did_and_pos_matching(uint32_t query_id, did_matcher_t *matchers, uint32_t nr_terms)
{
    while (true) {
        // This is either the initial loop, or we come back from a
        // set of matching DIDs. Whatever the case is, need to
        // warm up the iterator again by fetching next DIDs.
        if (!matchers_has_next_did(matchers, nr_terms))
            return;

        seek_did_t did_status;
        do {
            uint32_t did = get_max_did(matchers, nr_terms);
            did_status = seek_did(matchers, nr_terms, did);
            switch (did_status) {
            case END_OF_INDEX_TABLE:
                return;
            case DID_FOUND: {
                perform_pos_matching_for_did(query_id, matchers, nr_terms, did);
            } break;
            case DID_NOT_FOUND:
                break;
            }
        } while (did_status == DID_NOT_FOUND);
    }
}
