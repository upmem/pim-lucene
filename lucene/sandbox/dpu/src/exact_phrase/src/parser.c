#include <defs.h>     // for me
#include <stdbool.h>  // for bool, false, true
#include <stdint.h>   // for uint32_t, int32_t

#include "common.h"   // for mram_ptr_t, MAX_NR_TERMS
#include "decoder.h"  // for decode_vint_from, get_absolute_address_from
#include "term.h"     // for decoder_t

typedef struct parser_s {
    decoder_t *decoder;
    struct {
        int32_t nr_pos_left; // How many positions still to be read
        uint32_t current_pos; // Last position recorded during the parsing
        mram_ptr_t pos_end_addr;
    } pos_parser;
    struct {
        uint32_t current_did; // Either the first DID of a new segment, or the last DID read in the current segment.
        mram_ptr_t did_end_addr;
    } did_parser;
} parser_t;

#include "parser.h"   // for DOC_INFO, END_OF_FRAGMENT, abort_parse_did, abo...

static parser_t global_parsers[NR_TASKLETS][MAX_NR_TERMS];

// ============================================================================
// INIT PARSERS FUNCTIONS
// ============================================================================
static parser_t *
initialize_parser(mram_ptr_t mram_addr, uint32_t byte_size, uint32_t start_did, uint32_t term_id)
{
    parser_t *parser = &global_parsers[me()][term_id];
    initialize_decoder(parser->decoder, mram_addr);
    parser->did_parser.current_did = start_did;
    parser->did_parser.did_end_addr = mram_addr + byte_size;
    return parser;
}

parser_t *
setup_parser(uint32_t term_id, mram_ptr_t postings_address, uint32_t byte_size, uint32_t start_did)
{
    return initialize_parser(postings_address, byte_size, start_did, term_id);
}

static void
next_decoder(decoder_t *decoder, uint32_t id, void *ctx)
{
    parser_t *parsers = (parser_t *)ctx;
    parsers[id].decoder = decoder;
}

void
allocate_parsers(uint32_t nr_terms)
{
    decoder_pool_get(nr_terms, next_decoder, global_parsers[me()]);
}

static decoder_t *
next_decoder_release(uint32_t id, void *ctx)
{
    parser_t *parsers = (parser_t *)ctx;
    return parsers[id].decoder;
}

void
release_parsers(uint32_t nr_terms)
{
    decoder_pool_release(nr_terms, next_decoder_release, global_parsers[me()]);
}

// ============================================================================
// PARSER DID FUNCTIONS
// ============================================================================

static void
parse_length_and_freq(parser_t *parser, uint32_t *freq, unsigned int *len)
{
    int f = decode_zigzag_from(parser->decoder);
    if (f > 0) {
        *freq = (uint32_t)f;
        *len = decode_byte_from(parser->decoder);
    } else if (f == 0) {
        *freq = decode_vint_from(parser->decoder);
        *len = decode_vint_from(parser->decoder);
    } else {
        *freq = (uint32_t)-f;
        *len = decode_short_from(parser->decoder);
    }
}

parse_did_t
parse_did(parser_t *parser, uint32_t *did, uint32_t *freq, uint32_t *len)
{
    mram_ptr_t decoder_addr = get_absolute_address_from(parser->decoder);
    if (decoder_addr >= parser->did_parser.did_end_addr) {
        return END_OF_FRAGMENT;
    }

    uint32_t delta_did = decode_vint_from(parser->decoder);
    parse_length_and_freq(parser, freq, len);
    uint32_t next_did = parser->did_parser.current_did + delta_did;

    *did = parser->did_parser.current_did = next_did;

    return DOC_INFO;
}

void
abort_parse_did(parser_t *parser, uint32_t current_did_len)
{
    seek_decoder(parser->decoder, get_absolute_address_from(parser->decoder) + current_did_len);
}

// ============================================================================
// PARSER POS FUNCTIONS
// ============================================================================

// Sets up the parser to decode a series of positions. The parameter specifies
// the size of the position list, in bytes
void
prepare_to_parse_pos_list(parser_t *parser, uint32_t freq, uint32_t len)
{
    parser->pos_parser.pos_end_addr = get_absolute_address_from(parser->decoder) + len;
    parser->pos_parser.nr_pos_left = (int32_t)freq;
    parser->pos_parser.current_pos = 0;
}

// Gets the next position. Returns true if more positions are expected.
bool
parse_pos(parser_t *parser, uint32_t *pos)
{
    if (parser->pos_parser.nr_pos_left <= 0) {
        return false;
    }

    uint32_t this_pos = decode_vint_from(parser->decoder);

    parser->pos_parser.nr_pos_left--;
    parser->pos_parser.current_pos += this_pos;

    *pos = parser->pos_parser.current_pos;
    return true;
}

// Aborts the parsing of positions, moving the cursor to the next
// document or segment descriptor.
void
abort_parse_pos(parser_t *parser)
{
    seek_decoder(parser->decoder, parser->pos_parser.pos_end_addr);
}

mram_ptr_t
parser_get_curr_address(parser_t *parser)
{
    return get_absolute_address_from(parser->decoder);
}
