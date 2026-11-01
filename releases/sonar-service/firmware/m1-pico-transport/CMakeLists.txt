cmake_minimum_required(VERSION 3.13)
if(NOT DEFINED PICO_SDK_PATH)
    set(PICO_SDK_PATH $ENV{PICO_SDK_PATH})
endif()
if(NOT PICO_SDK_PATH)
    message(FATAL_ERROR "Set PICO_SDK_PATH to the Raspberry Pi Pico SDK")
endif()
include(${PICO_SDK_PATH}/external/pico_sdk_import.cmake)
project(no5_pico_m1 C CXX ASM)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
pico_sdk_init()

option(NO5_SYNTHETIC_TEST "Generate synthetic pings instead of ADC-triggered captures" OFF)

add_executable(no5_pico_m1
    main.c
    usb_descriptors.c
)
target_include_directories(no5_pico_m1 PRIVATE ${CMAKE_CURRENT_LIST_DIR})
target_link_libraries(no5_pico_m1 PRIVATE
    pico_stdlib
    pico_unique_id
    hardware_adc
    hardware_dma
    hardware_gpio
    tinyusb_device
    tinyusb_board
)
target_compile_definitions(no5_pico_m1 PRIVATE CFG_TUSB_OS=OPT_OS_PICO)
if(NO5_SYNTHETIC_TEST)
    target_compile_definitions(no5_pico_m1 PRIVATE NO5_SYNTHETIC_TEST=1)
endif()
pico_add_extra_outputs(no5_pico_m1)
