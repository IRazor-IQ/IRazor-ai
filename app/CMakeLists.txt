cmake_minimum_required(VERSION 3.25.1)

project("irazor_native" C CXX)

add_library(
    irazor_native
    SHARED
    src/main/cpp/native_bridge.cpp
)

target_include_directories(
    irazor_native
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(EGL-lib EGL)
find_library(GLESv2-lib GLESv2)

target_link_libraries(
    irazor_native
    ${log-lib}
    ${android-lib}
    ${EGL-lib}
    ${GLESv2-lib}
    android
    z
)

set_target_properties(
    irazor_native
    PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
)
