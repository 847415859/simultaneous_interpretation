cmake_minimum_required(VERSION 3.22.1)
project(yinanyi_llm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp b11181 源码在仓库根目录的 third_party 下（不提交，见 README）
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp-b11181)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# OpenMP 的线程会忙等，和识别模型抢 CPU 时速度掉好几倍；改用 ggml 自带线程池
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# 骁龙 8 Gen 3 等较新 ARM 处理器支持 dotprod / i8mm，量化矩阵乘法快很多
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(yinanyi_llm SHARED translator.cpp)
target_include_directories(yinanyi_llm PRIVATE ${LLAMA_SRC}/include)
target_link_libraries(yinanyi_llm llama android log)
