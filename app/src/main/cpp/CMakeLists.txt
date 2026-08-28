cmake_minimum_required(VERSION 3.22.1)
project(camera_core LANGUAGES CXX)

add_library(camera_core SHARED camera_core.cpp)

target_compile_features(camera_core PRIVATE cxx_std_20)
target_compile_options(camera_core PRIVATE -O3 -ffast-math -fvisibility=hidden)
