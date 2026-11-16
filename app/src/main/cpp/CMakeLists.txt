cmake_minimum_required(VERSION 3.22.1)
project(retroloudness)

set(EBUR128_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libebur128/ebur128)

add_library(ebur128 STATIC ${EBUR128_DIR}/ebur128.c)
target_include_directories(ebur128 PUBLIC ${EBUR128_DIR})
# Prefer NDK BSD queue.h; fall back to vendored header if missing.
if(EXISTS ${EBUR128_DIR}/queue/sys/queue.h)
  # available as fallback include path
  target_include_directories(ebur128 PRIVATE ${EBUR128_DIR}/queue)
endif()

add_library(retroloudness SHARED loudness_jni.cpp)
target_include_directories(retroloudness PRIVATE ${EBUR128_DIR})
find_library(log-lib log)
target_link_libraries(retroloudness ebur128 ${log-lib})
