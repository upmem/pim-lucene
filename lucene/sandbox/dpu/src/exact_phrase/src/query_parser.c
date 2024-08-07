#include <stdint.h>        // for uint32_t, uint8_t

#include "common.h"        // for mram_ptr_t
#include "decoder.h"       // for get_absolute_address_from, decode_vint_from
#include "query_parser.h"  // for query_parser_t, init_query_parser, read_field
#include "term.h"          // for term_t

void
init_query_parser(query_parser_t *parser, mram_ptr_t query)
{
    parser->nr_terms = 0;
    parser->decoder = decoder_pool_get_one();
    initialize_decoder(parser->decoder, query);
    parser->curr_ptr = query;
}

void
read_query_type(query_parser_t *parser, uint8_t *query_type)
{
    *query_type = decode_byte_from(parser->decoder);
    parser->curr_ptr = get_absolute_address_from(parser->decoder);
}

void
read_field(query_parser_t *parser, term_t *field)
{
    read_term(parser, field);
}

void
read_nr_terms(query_parser_t *parser, uint32_t *nr_terms)
{

    seek_decoder(parser->decoder, parser->curr_ptr);
    *nr_terms = decode_vint_from(parser->decoder);
    parser->nr_terms = *nr_terms;
    parser->curr_ptr = get_absolute_address_from(parser->decoder);
}

void
read_term(query_parser_t *parser, term_t *term)
{

    seek_decoder(parser->decoder, parser->curr_ptr);
    term->size = decode_vint_from(parser->decoder);
    parser->curr_ptr = get_absolute_address_from(parser->decoder) + term->size;
    term->term_decoder = parser->decoder;
}

void
release_query_parser(query_parser_t *parser)
{
    decoder_pool_release_one(parser->decoder);
}
