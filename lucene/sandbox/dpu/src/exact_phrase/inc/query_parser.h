#ifndef QUERY_PARSER_H_
#define QUERY_PARSER_H_

#include <mram.h>
#include <stdbool.h>
#include <stdint.h>

#include "common.h"
#include "decoder.h"
#include "term.h"

/**
 * Structure used to parse the query
 */
typedef struct {
    decoder_t *decoder;
    uint32_t nr_terms;
    mram_ptr_t curr_ptr;
} query_parser_t;

void
init_query_parser(query_parser_t *parser, mram_ptr_t query);
void
read_query_type(query_parser_t *parser, uint8_t *query_type);
void
read_field(query_parser_t *parser, term_t *field);
void
read_nr_terms(query_parser_t *parser, uint32_t *nr_terms);
void
read_term(query_parser_t *parser, term_t *term);
void
release_query_parser(query_parser_t *parser);

#endif /* QUERY_PARSER_H_ */
