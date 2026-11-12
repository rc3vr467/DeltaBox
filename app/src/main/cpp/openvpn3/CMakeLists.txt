# OpenVPN 3 JNI library for DeltaBox.
#
# STATUS: NOT COMPILED OR RUN in this environment. Only built when Gradle is invoked with
# -Pdeltabox.openvpn3=true (see app/build.gradle.kts). Default builds do not use this file.
#
# Dependencies and target names are taken from the openvpn3 core's own build logic:
#   cmake/findcoredeps.cmake  -> asio::asio, lz4::lz4, fmt::fmt, JsonCpp::JsonCpp, Threads
#   vcpkg.json                -> asio, fmt, jsoncpp, lz4, openssl (xxhash is optional, HAVE_XXHASH, not used)
# OpenSSL uses the standard CMake FindOpenSSL targets (OpenSSL::SSL, OpenSSL::Crypto).
# Required compile definitions match findcoredeps.cmake: ASIO_STANDALONE, USE_ASIO, HAVE_LZ4,
# USE_OPENSSL and HAVE_JSONCPP (added when JsonCpp is found).

cmake_minimum_required(VERSION 3.22)
project(ovpn3_jni CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

set(OVPN3_DIR ${CMAKE_CURRENT_SOURCE_DIR})

# Per-ABI vcpkg installs: <OVPN3_DEPS_ROOT>/<ANDROID_ABI>/ (set by the Android toolchain).
if(NOT DEFINED OVPN3_DEPS_ROOT)
    message(FATAL_ERROR "Pass -DOVPN3_DEPS_ROOT=<dir with per-ABI installs>")
endif()
list(APPEND CMAKE_PREFIX_PATH "${OVPN3_DEPS_ROOT}/${ANDROID_ABI}")

find_package(Threads REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(asio CONFIG REQUIRED)
find_package(lz4 CONFIG REQUIRED)
find_package(fmt CONFIG REQUIRED)
find_package(jsoncpp CONFIG REQUIRED)
find_library(ANDROID_LOG_LIB log REQUIRED)

add_library(ovpn3_jni SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/ovpn3_jni.cpp
    ${OVPN3_DIR}/client/ovpncli.cpp
)

target_include_directories(ovpn3_jni PRIVATE ${OVPN3_DIR} ${OVPN3_DIR}/client)

target_compile_definitions(ovpn3_jni PRIVATE
    ASIO_STANDALONE
    USE_ASIO
    HAVE_LZ4
    USE_OPENSSL
    HAVE_JSONCPP
)

target_link_libraries(ovpn3_jni PRIVATE
    ${ANDROID_LOG_LIB}
    Threads::Threads
    OpenSSL::SSL
    OpenSSL::Crypto
    asio::asio
    lz4::lz4
    fmt::fmt
    JsonCpp::JsonCpp
)
