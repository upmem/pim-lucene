#ifndef PARSER_H_
#define PARSER_H_

#include <stdbool.h>
#include <stdint.h>

#include "common.h"
#include "term.h"

/**
 * Structure used to parse document ids and positions from the index
 */
typedef struct parser_s parser_t;

/**
 * Type returned by the function that parses the document id
 */
typedef enum {
    DOC_INFO,
    SKIP_INFO, // unused
    END_OF_FRAGMENT,
} parse_did_t;

parse_did_t
parse_did(parser_t *parser, uint32_t *did, uint32_t *freq, uint32_t *len);
void
abort_parse_did(parser_t *parser, uint32_t current_did_len);

void
prepare_to_parse_pos_list(parser_t *parser, uint32_t freq, uint32_t len);
bool
parse_pos(parser_t *parser, uint32_t *pos);
void
abort_parse_pos(parser_t *parser);

void
allocate_parsers(uint32_t nr_terms);
parser_t *
setup_parser(uint32_t term_id, mram_ptr_t postings_address, uint32_t byte_size, uint32_t start_did);
void
release_parsers(uint32_t nr_terms);

mram_ptr_t
parser_get_curr_address(parser_t *parser);

#endif /* PARSER_H_ */
