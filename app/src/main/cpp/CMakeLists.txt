
cmake_minimum_required(VERSION 3.22.1)

project("sonygphototest")

# Lokasi library native ARM64
set(GPHOTO_LIB_DIR
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}
)

# Library utama libgphoto2
add_library(gphoto2 SHARED IMPORTED)
set_target_properties(gphoto2 PROPERTIES
    IMPORTED_LOCATION
    ${GPHOTO_LIB_DIR}/libgphoto2.so
)

# Library port USB
add_library(gphoto2_port SHARED IMPORTED)
set_target_properties(gphoto2_port PROPERTIES
    IMPORTED_LOCATION
    ${GPHOTO_LIB_DIR}/libgphoto2_port.so
)

# Library USB
add_library(usb1 SHARED IMPORTED)
set_target_properties(usb1 PROPERTIES
    IMPORTED_LOCATION
    ${GPHOTO_LIB_DIR}/libusb-1.0.so
)

# Library libltdl
add_library(ltdl SHARED IMPORTED)
set_target_properties(ltdl PROPERTIES
    IMPORTED_LOCATION
    ${GPHOTO_LIB_DIR}/libltdl.so
)

# JNI test library
add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${CMAKE_SOURCE_DIR}/include
)

# Link library ke JNI
target_link_libraries(${CMAKE_PROJECT_NAME}
    gphoto2
    gphoto2_port
    usb1
    ltdl
    android
    log
)

