#include <stdio.h>
#include <seqread.h>
#include "../read_index.h"

/**
* Test for the function get_block_from_table
* A small index is stored in MRAM
* Test to find two fields field1 and field2 in the block table
* Test to find two terms green and orange in the block table of each field
*/

__mram uint8_t index_mram[235] = {0x18, 0x31, 0x87, 0x1, 0x6, 0x66, 0x69, 0x65, 0x6c, 0x64, 0x32, 0x25, 0x10, 0x6, 0x66,
0x69, 0x65, 0x6c, 0x64, 0x31, 0x0, 0x7, 0x2, 0x69, 0x64, 0x0, 0x0, 0x19, 0x3, 0x41, 0x41, 0x41, 0x0, 0x0, 0xe, 0x5,
0x62, 0x6c, 0x61, 0x63, 0x6b, 0x0, 0xe, 0x35, 0x5, 0x62, 0x72, 0x6f, 0x77, 0x6e, 0x0, 0x35, 0x56, 0x0, 0x4, 0x3, 0x41,
0x41, 0x42, 0x4, 0x5, 0x3, 0x41, 0x41, 0x43, 0x9, 0x5, 0xe, 0xa, 0x4, 0x62, 0x6c, 0x75, 0x65, 0x18, 0xa, 0x5, 0x67,
0x72, 0x65, 0x65, 0x6e, 0x22, 0x5, 0x4, 0x70, 0x69, 0x6e, 0x6b, 0x27, 0x5, 0x3, 0x72, 0x65, 0x64, 0x2c, 0x5, 0x6, 0x79,
0x65, 0x6c, 0x6c, 0x6f, 0x77, 0x31, 0xa, 0x3b, 0x5, 0x5, 0x67, 0x72, 0x65, 0x65, 0x6e, 0x40, 0x5, 0x6, 0x6f, 0x72, 0x61,
0x6e, 0x67, 0x65, 0x45, 0x6, 0x3, 0x72, 0x65, 0x64, 0x4b, 0xb, 0x5, 0x77, 0x68, 0x69, 0x74, 0x65, 0x56, 0xa, 0x0, 0x2,
0x1, 0x0, 0x1, 0x1, 0x1, 0x0, 0x0, 0x2, 0x0, 0x1, 0x1, 0x0, 0x0, 0x0, 0x1, 0x1, 0x1, 0x2, 0x0, 0x1, 0x1, 0x0, 0x1, 0x0,
0x1, 0x1, 0x2, 0x1, 0x0, 0x1, 0x1, 0x1, 0x1, 0x0, 0x1, 0x1, 0x1, 0x2, 0x0, 0x1, 0x1, 0x2, 0x0, 0x0, 0x1, 0x1, 0x0, 0x0,
0x0, 0x1, 0x1, 0x2, 0x1, 0x0, 0x1, 0x1, 0x0, 0x2, 0x0, 0x1, 0x1, 0x1, 0x1, 0x0, 0x1, 0x1, 0x0, 0x0, 0x0, 0x2, 0x2, 0x1,
0x2, 0x0, 0x0, 0x2, 0x2, 0x0, 0x4, 0x1, 0x0, 0x1, 0x1, 0x1, 0x0, 0x0, 0x1, 0x1, 0x2, 0x2, 0x0, 0x1, 0x1};

uint8_t field1_arr[6] = {0x66, 0x69, 0x65, 0x6c, 0x64, 0x31};
struct Term field1 = {field1_arr, 6};
uint32_t field1_addr = 7;

uint8_t field2_arr[6] = {0x66, 0x69, 0x65, 0x6c, 0x64, 0x32};
struct Term field2 = {field2_arr, 6};
uint32_t field2_addr = 16;

uint8_t field3_arr[6] = {0x66, 0x69, 0x65, 0x6c, 0x32, 0x64};
struct Term field3 = {field3_arr, 6};

uint8_t green_arr[5] = {0x67, 0x72, 0x65, 0x65, 0x6e};
struct Term green = {green_arr, 5};
uint32_t green_addr = 14;

uint8_t orange_arr[6] = {0x6f, 0x72, 0x61, 0x6e, 0x67, 0x65};
struct Term orange = {orange_arr, 6};
uint32_t orange_addr = 53;

struct Block term_block;

int main() {

    // init sequential reader to read the index
    seqreader_buffer_t buffer = seqread_alloc();
    seqreader_t seqread;

    // look for the field "field1"
    const uint8_t* index_ptr = seqread_init(buffer, index_mram, &seqread);
    //skip offsets first
    int block_offset = readVInt_mram(&index_ptr, &seqread);
    int block_list_offset = readVInt_mram(&index_ptr, &seqread);
    int postings_offset = readVInt_mram(&index_ptr, &seqread);
    __mram_ptr uint8_t* index_begin_addr = seqread_tell((void*)index_ptr, &seqread);

    get_block_from_table(index_ptr, &seqread, &field1, &term_block);
    if(term_block.block_address == field1_addr) {
        printf("field1 OK\n");
    } else {
        printf("field1 KO: %d\n", (int)term_block.term);
    }
    // search for the term "green"
    __mram_ptr uint8_t* block_address = index_begin_addr + block_offset + term_block.block_address;
    index_ptr = seqread_seek(block_address, &seqread);
    get_block_from_table(index_ptr, &seqread, &green, &term_block);
    if(term_block.block_address == green_addr) {
        printf("green OK\n");
    } else {
        printf("green KO: %d\n", (int)term_block.term);
    }
    // look for the green term postings
    /*
    index_ptr = seqread_seek(block_address, &seqread);
    __mram_ptr uint8_t* postings_addr =
                    get_term_postings_from_index(index_ptr, &seqread,
                            green, index_begin_addr + block_list_offset,
                            index_begin_addr + postings_offset, &term_block);
    // read postings
    uint32_t doc_id = readVInt_mram(&postings_addr, &seqread);
    */

    // look for field "field2"
    index_ptr = seqread_init(buffer, index_mram, &seqread);
    //skip offsets first
    block_offset = readVInt_mram(&index_ptr, &seqread);
    block_list_offset = readVInt_mram(&index_ptr, &seqread);
    postings_offset = readVInt_mram(&index_ptr, &seqread);
    get_block_from_table(index_ptr, &seqread, &field2, &term_block);
    if(term_block.block_address == field2_addr) {
        printf("field2 OK\n");
    } else {
        printf("field2 KO: %d\n", (int)term_block.term);
    }
    //searching for the term orange
    block_address = index_begin_addr + block_offset + term_block.block_address;
    index_ptr = seqread_seek(block_address, &seqread);
    get_block_from_table(index_ptr, &seqread, &orange, &term_block);
    if(term_block.block_address == orange_addr) {
        printf("orange OK\n");
    } else {
        printf("orange KO: %d\n", (int)term_block.term);
    }

    // look for field "field3"
    index_ptr = seqread_init(buffer, index_mram, &seqread);
    //skip offsets first
    block_offset = readVInt_mram(&index_ptr, &seqread);
    block_list_offset = readVInt_mram(&index_ptr, &seqread);
    postings_offset = readVInt_mram(&index_ptr, &seqread);
    get_block_from_table(index_ptr, &seqread, &field3, &term_block);
    if(term_block.term == 0) {
        printf("field3 OK\n");
    }
    else {
        printf("field3 KO: %d\n", (int)term_block.term);
    }

    return 0;
}
