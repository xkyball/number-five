cmake_minimum_required(VERSION 3.31)
project(no5_sonar_gpu LANGUAGES CXX)
add_library(no5_sonar_gpu SHARED sonar_gpu_stream.cpp sonar_cpu_reference.cpp)
target_compile_features(no5_sonar_gpu PRIVATE cxx_std_20)
# Fixture and production use the same optimized arithmetic. No fast-math/FMA
# contraction: source-aligned Float/Double operation order remains reviewable.
target_compile_options(no5_sonar_gpu PRIVATE -O2 -fno-fast-math -ffp-contract=off -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(no5_sonar_gpu PRIVATE android EGL GLESv3 log)
option(NO5_SONAR_FAULT_TEST "Own-context fault injection for isolated offscreen fixture only" OFF)
if(NO5_SONAR_FAULT_TEST)
  target_compile_definitions(no5_sonar_gpu PRIVATE NO5_SONAR_FAULT_TEST=1)
endif()

# Comparison library is available only to an explicit isolated private fixture.
option(NO5_SONAR_COMPARISON "Build frozen previous renderer for private comparison" OFF)
if(NO5_SONAR_COMPARISON)
  if(NOT NO5_SONAR_FAULT_TEST OR NOT EXISTS "${NO5_SONAR_LEGACY_SOURCE}")
    message(FATAL_ERROR "Comparison requires explicit private fixture and frozen source")
  endif()
  add_library(no5_sonar_legacy SHARED "${NO5_SONAR_LEGACY_SOURCE}")
  target_compile_features(no5_sonar_legacy PRIVATE cxx_std_20)
  target_compile_options(no5_sonar_legacy PRIVATE -O2 -fno-fast-math -ffp-contract=off -Wall -Wextra -Werror -fvisibility=hidden)
  target_link_libraries(no5_sonar_legacy PRIVATE android EGL GLESv3 log)
endif()

# Frozen comparison library exists only in the separate benchmark variant.
if(NO5_SONAR_QUIET_BASELINE)
 add_library(sonar_quiet_baseline SHARED "${CMAKE_CURRENT_SOURCE_DIR}/../../../benchmark/cpp/sonar_quiet_baseline.cpp")
 target_compile_features(sonar_quiet_baseline PRIVATE cxx_std_20)
 target_compile_options(sonar_quiet_baseline PRIVATE -O2 -fno-fast-math -ffp-contract=off -Wall -Wextra -Werror)
 target_link_libraries(sonar_quiet_baseline android EGL GLESv3 log)
endif()
