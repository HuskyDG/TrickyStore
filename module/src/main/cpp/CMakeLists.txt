cmake_minimum_required(VERSION 3.28)
project(sample)

if (CCACHE)
    set(CMAKE_CXX_COMPILER_LAUNCHER ${CCACHE})
    set(CMAKE_C_COMPILER_LAUNCHER ${CCACHE})
endif ()

find_package(cxx REQUIRED CONFIG)
link_libraries(cxx::cxx)

find_program(CCACHE ccache)

set(LINKER_FLAGS "-ffixed-x18 -Wl,--hash-style=both")

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-exceptions -fno-rtti")

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} ${LINKER_FLAGS}")
set(CMAKE_MODULE_LINKER_FLAGS "${CMAKE_MODULE_LINKER_FLAGS} ${LINKER_FLAGS}")

add_library(elf_util STATIC elf_util/elf_util.cpp)
add_library(my_logging STATIC logging/logging.cpp)

add_subdirectory(external)

target_include_directories(my_logging PUBLIC logging/include)
target_include_directories(elf_util PUBLIC elf_util/include)

target_link_libraries(my_logging log)
target_link_libraries(elf_util lsplt my_logging)

# libutils stub
add_library(utils SHARED binder/stub_utils.cpp)
target_compile_options(utils PRIVATE -fvisibility=default -fno-visibility-inlines-hidden)
target_include_directories(utils PUBLIC binder/include)

# libbinder stub
add_library(binder SHARED binder/stub_binder.cpp)
target_include_directories(binder PUBLIC binder/include)
target_link_libraries(binder PRIVATE utils)

add_executable(libinject.so inject/main.cpp inject/utils.cpp)
target_link_libraries(libinject.so PRIVATE lsplt my_logging)
target_compile_options(libinject.so PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)

add_library(${MODULE_NAME} SHARED binder_interceptor.cpp)
target_link_libraries(${MODULE_NAME} PRIVATE log binder utils elf_util my_logging)
target_compile_options(${MODULE_NAME} PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)

add_library(tszygisk SHARED zygisk/main.cpp)
target_link_libraries(tszygisk PRIVATE log my_logging glaze::glaze)
