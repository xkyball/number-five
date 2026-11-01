// Offline candidate entry point; not included in the host comparison build.
#include "app.h"
static no5_app_t app;
int main(void) {
    no5_app_init(&app);
    while(true) no5_app_step(&app);
}
