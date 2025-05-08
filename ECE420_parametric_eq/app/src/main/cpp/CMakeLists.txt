cmake_minimum_required(VERSION 3.22.1)
project("ece420_parametric_eq")

# 1) Locate your kiss_fft directory
set(KISSFFT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/kiss_fft)

# 2) Gather only the core .c files
set(KISSFFT_SRC
        ${KISSFFT_DIR}/kiss_fft.c
        ${KISSFFT_DIR}/kiss_fftr.c
)

# 3) Build your native library
add_library(ece420_parametric_eq SHARED
        dsp_native.cpp
        ${KISSFFT_SRC}
)

# 4) Make the headers visible
target_include_directories(ece420_parametric_eq PRIVATE
        ${KISSFFT_DIR}
)

# 5) Link Android system libs
target_link_libraries(ece420_parametric_eq
        android
        log
)