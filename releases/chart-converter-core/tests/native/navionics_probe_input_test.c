/* Own host-portable argument controls only; no Original or reader calls. */
#include "no5_probe_input.h"
#include <assert.h>

int main(void)
{
    char program[] = "own-test";
    char path[] = "explicit input.bin";
    char empty[] = "";
    char *valid[] = {program, path, 0};
    char *missing[] = {program, 0};
    char *blank[] = {program, empty, 0};
    char *extra[] = {program, path, path, 0};
    assert(no5_probe_input_path(2, valid) == path);
    assert(no5_probe_input_path(0, 0) == 0);
    assert(no5_probe_input_path(2, 0) == 0);
    assert(no5_probe_input_path(1, missing) == 0);
    assert(no5_probe_input_path(2, missing) == 0);
    assert(no5_probe_input_path(2, blank) == 0);
    assert(no5_probe_input_path(3, extra) == 0);
    assert(no5_probe_input_path(-1, valid) == 0);
    return 0;
}
