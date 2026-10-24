# CMakeLists.txt for the Android usbmuxd bridge
#
# NEW file for this project. NOT run through CMake/NDK yet — you will
# very likely need to fix paths/flags here once you actually try to
# build. Treat this as a documented starting point, not a finished,
# verified build script.
#
# Directory layout this file assumes (adjust paths to match reality):
#
#   app/src/main/cpp/
#     CMakeLists.txt              <- this file
#     usb_android.c               <- patched (from this bundle)
#     usb_android.h               <- patched (from this bundle)
#     usb_jni.c                   <- new (from this bundle)
#     usbmuxd_embedded.c          <- new (from this bundle)
#     usbmuxd-src/                <- UNMODIFIED files copied from your
#                                     uploaded usbmuxd-master.zip:
#                                     client.c client.h device.c device.h
#                                     log.c log.h preflight.c preflight.h
#                                     conf.c conf.h utils.c utils.h
#                                     usbmuxd-proto.h
#                                     (do NOT copy main.c or the original
#                                      usb.c/usb.h — those are replaced)
#     vendor/
#       libusb/            <- prebuilt or self-cross-compiled per ABI
#       libplist/          <- from libplist-master.zip, built normally
#                              (no Android-specific changes needed)
#       libusbmuxd/        <- from libusbmuxd-master.zip, built normally
#                              (no Android-specific changes needed)
#       libimobiledevice/  <- from libimobiledevice-master.zip, built
#                              normally (no Android-specific changes
#                              needed — it never touches libusb directly)
#
# libplist, libusbmuxd, and libimobiledevice do NOT need patching for
# Android at all — the only file in the whole stack that talks to raw
# USB is usbmuxd/src/usb.c, which this bundle replaces with
# usb_android.c. Build those three libraries as ordinary Android NDK
# static/shared libraries using their existing CMake/autotools build
# systems; nothing in this CMakeLists.txt duplicates that work.

cmake_minimum_required(VERSION 3.22.1)
project(usbmuxd_native C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- Prebuilt dependency libraries -----------------------------------
# These paths assume you built each dependency separately per-ABI and
# placed the results under vendor/<lib>/<ABI>/. Adjust to taste (a
# single combined "prefab" package is another option if you want to
# package these as proper Gradle dependencies instead).

set(VENDOR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor)
set(JNILIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs)

add_library(libusb_prebuilt SHARED IMPORTED)
set_target_properties(libusb_prebuilt PROPERTIES
    IMPORTED_LOCATION ${JNILIBS_DIR}/${ANDROID_ABI}/libusb1.0.so)

add_library(libplist_prebuilt SHARED IMPORTED)
set_target_properties(libplist_prebuilt PROPERTIES
    IMPORTED_LOCATION ${JNILIBS_DIR}/${ANDROID_ABI}/libplist-2.0.so)

add_library(libimobiledevice_glue_prebuilt SHARED IMPORTED)
set_target_properties(libimobiledevice_glue_prebuilt PROPERTIES
    IMPORTED_LOCATION ${JNILIBS_DIR}/${ANDROID_ABI}/libimobiledevice-glue-1.0.so)

add_library(libusbmuxd_prebuilt SHARED IMPORTED)
set_target_properties(libusbmuxd_prebuilt PROPERTIES
    IMPORTED_LOCATION ${JNILIBS_DIR}/${ANDROID_ABI}/libusbmuxd-2.0.so)

add_library(libcrypto_prebuilt SHARED IMPORTED)
set_target_properties(libcrypto_prebuilt PROPERTIES
    IMPORTED_LOCATION ${JNILIBS_DIR}/${ANDROID_ABI}/libcrypto.so)

add_library(libssl_prebuilt SHARED IMPORTED)
set_target_properties(libssl_prebuilt PROPERTIES
    IMPORTED_LOCATION ${JNILIBS_DIR}/${ANDROID_ABI}/libssl.so)

add_library(libimobiledevice_prebuilt SHARED IMPORTED)
set_target_properties(libimobiledevice_prebuilt PROPERTIES
    IMPORTED_LOCATION ${JNILIBS_DIR}/${ANDROID_ABI}/libimobiledevice-1.0.so)

include_directories(
    ${VENDOR_DIR}/libusb/include
    ${VENDOR_DIR}/libplist/include
    ${VENDOR_DIR}/libimobiledevice-glue/include
    ${VENDOR_DIR}/libusbmuxd/include
    ${VENDOR_DIR}/openssl/include
    ${VENDOR_DIR}/libimobiledevice/include
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/usbmuxd-src
)

# --- The embedded, patched usbmuxd core --------------------------------
# USBMUXD_ANDROID guards every Android-specific change inside
# usb_android.c (grep for "ANDROID PATCH" to see all of them).
add_definitions(-DUSBMUXD_ANDROID -DHAVE_CONFIG_H=1)

add_library(usbmuxd_native SHARED
    usb_android.c
    usb_jni.c
    lockdown_jni.c
    install_jni.c
    usbmuxd_embedded.c
    usbmuxd-src/device.c
    usbmuxd-src/client.c
    usbmuxd-src/log.c
    usbmuxd-src/preflight.c
    usbmuxd-src/conf.c
    usbmuxd-src/utils.c
)

target_link_libraries(usbmuxd_native
    libusb_prebuilt
    libplist_prebuilt
    libimobiledevice_glue_prebuilt
    libusbmuxd_prebuilt
    libcrypto_prebuilt
    libssl_prebuilt
    libimobiledevice_prebuilt
    log
)

# --- Mach-O Code Signer (zsign port) ---------------------------------
add_library(signer_native SHARED
    signer_jni.cpp
    zsigner_api.cpp
    zsign/archo.cpp
    zsign/bundle.cpp
    zsign/certcheck.cpp
    zsign/macho.cpp
    zsign/metadata.cpp
    zsign/openssl.cpp
    zsign/signing.cpp
    zsign/common/archive.cpp
    zsign/common/fs.cpp
    zsign/common/json.cpp
    zsign/common/log.cpp
    zsign/common/sha.cpp
    zsign/common/timer.cpp
    zsign/common/util.cpp
    zsign/third-party/minizip/ioapi.c
    zsign/third-party/minizip/zip.c
    zsign/third-party/minizip/unzip.c
)

target_include_directories(signer_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/zsign
    ${CMAKE_CURRENT_SOURCE_DIR}/zsign/common
    ${CMAKE_CURRENT_SOURCE_DIR}/zsign/third-party/minizip
    ${CMAKE_CURRENT_SOURCE_DIR}/zsign/third-party/zlib
    ${VENDOR_DIR}/openssl/include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_definitions(signer_native PRIVATE
    ZSIGN_VERSION="0.0.0-android"
)

target_link_libraries(signer_native
    libcrypto_prebuilt
    z
    log
)

# NOTE: usbmuxd-src/*.c were written expecting a config.h from
# autotools (HAVE_CONFIG_H). Defining HAVE_CONFIG_H=0 above disables
# that include, but some files may reference macros normally supplied
# by that generated config.h (e.g. PACKAGE_VERSION). You will likely
# need to add a small hand-written config.h with reasonable stub
# values the first time you actually build this. That is expected,
# ordinary porting work — not a sign anything above is wrong.
