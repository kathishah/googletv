cmake_minimum_required(VERSION 3.22)
project(heif_tile_probe LANGUAGES C CXX)

if(ANDROID)
    include(FetchContent)

    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(ENABLE_SDL OFF CACHE BOOL "" FORCE)
    set(ENABLE_DECODER ON CACHE BOOL "" FORCE)
    set(ENABLE_ENCODER OFF CACHE BOOL "" FORCE)
    set(ENABLE_SHERLOCK265 OFF CACHE BOOL "" FORCE)
    set(ENABLE_INTERNAL_DEVELOPMENT_TOOLS OFF CACHE BOOL "" FORCE)
    set(WITH_FUZZERS OFF CACHE BOOL "" FORCE)

    FetchContent_Declare(
        libde265
        GIT_REPOSITORY https://github.com/strukturag/libde265.git
        GIT_TAG ba62bf4cfb3242f3bf0a45617ff09e35236e4d82
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(libde265)

    set(WITH_LIBDE265 ON CACHE BOOL "" FORCE)
    set(WITH_LIBDE265_PLUGIN OFF CACHE BOOL "" FORCE)
    unset(LIBDE265_FOUND CACHE)
    unset(LIBDE265_FOUND)
    set(WITH_X265 OFF CACHE BOOL "" FORCE)
    set(WITH_KVAZAAR OFF CACHE BOOL "" FORCE)
    set(WITH_X264 OFF CACHE BOOL "" FORCE)
    set(WITH_OpenH264_DECODER OFF CACHE BOOL "" FORCE)
    set(WITH_DAV1D OFF CACHE BOOL "" FORCE)
    set(WITH_AOM_DECODER OFF CACHE BOOL "" FORCE)
    set(WITH_AOM_ENCODER OFF CACHE BOOL "" FORCE)
    set(WITH_FFMPEG_DECODER OFF CACHE BOOL "" FORCE)
    set(WITH_JPEG_DECODER OFF CACHE BOOL "" FORCE)
    set(WITH_JPEG_ENCODER OFF CACHE BOOL "" FORCE)
    set(WITH_LIBSHARPYUV OFF CACHE BOOL "" FORCE)
    set(WITH_UNCOMPRESSED_CODEC OFF CACHE BOOL "" FORCE)
    set(WITH_HEADER_COMPRESSION OFF CACHE BOOL "" FORCE)
    set(ENABLE_PLUGIN_LOADING OFF CACHE BOOL "" FORCE)
    set(ENABLE_MULTITHREADING_SUPPORT ON CACHE BOOL "" FORCE)
    set(ENABLE_PARALLEL_TILE_DECODING OFF CACHE BOOL "" FORCE)
    set(WITH_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WITH_EXAMPLE_HEIF_THUMB OFF CACHE BOOL "" FORCE)
    set(WITH_EXAMPLE_HEIF_VIEW OFF CACHE BOOL "" FORCE)
    set(WITH_GDK_PIXBUF OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(WITH_FUZZERS OFF CACHE BOOL "" FORCE)
    set(WITH_REDUCED_VISIBILITY OFF CACHE BOOL "" FORCE)

    set(LIBDE265_INCLUDE_DIR "${libde265_SOURCE_DIR}" CACHE PATH "" FORCE)
    set(LIBDE265_LIBRARY de265 CACHE STRING "" FORCE)

    FetchContent_Declare(
        libheif
        GIT_REPOSITORY https://github.com/strukturag/libheif.git
        GIT_TAG 413e2a87e6a70b3eccc3a3adc5801179dd2d9e00
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(libheif)

    # libde265 generates its public version header into <build>/libde265, but
    # its target only exposes that directory privately. libheif includes the
    # public header while compiling its decoder backend.
    target_include_directories(heif PRIVATE "${libde265_BINARY_DIR}")

    add_library(heif_tile_probe SHARED heif_tile_probe.cpp)
    target_include_directories(heif_tile_probe PRIVATE
        "${libheif_SOURCE_DIR}/libheif/api"
        "${libheif_BINARY_DIR}"
    )
    target_link_libraries(heif_tile_probe PRIVATE heif log)
    target_compile_features(heif_tile_probe PRIVATE cxx_std_17)
else()
    find_package(PkgConfig REQUIRED)
    pkg_check_modules(HEIF REQUIRED IMPORTED_TARGET libheif)
    add_executable(heif_tile_probe heif_tile_probe.cpp)
    target_link_libraries(heif_tile_probe PRIVATE PkgConfig::HEIF)
    target_compile_features(heif_tile_probe PRIVATE cxx_std_17)
endif()
