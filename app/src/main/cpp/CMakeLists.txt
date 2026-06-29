cmake_minimum_required(VERSION 3.22)
project(corepty C)

# Target only arm64
set(CMAKE_ANDROID_ARCH_ABI arm64-v8a)

add_library(corepty SHARED pty.c)

# On Android Bionic, forkpty() is in libc — no separate libutil needed
target_link_libraries(corepty log)
