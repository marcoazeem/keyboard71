cmake_minimum_required(VERSION 3.22.1)

project(gl2jni_stub)

add_library(
    gl2jni
    SHARED
    gl2jni_stub.cpp
)

find_library(
    log-lib
    log
)

find_library(
    glesv2-lib
    GLESv2
)

target_link_libraries(
    gl2jni
    ${log-lib}
    ${glesv2-lib}
)
