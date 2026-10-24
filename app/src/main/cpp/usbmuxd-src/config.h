#ifndef CONFIG_H
#define CONFIG_H

/* Name of package */
#define PACKAGE_NAME "usbmuxd"

/* Version number of package */
#define PACKAGE_VERSION "1.1.2"

/* Define to the full name and version of this package. */
#define PACKAGE_STRING "usbmuxd 1.1.2"

/* Define to the address where bug reports for this package should be sent. */
#define PACKAGE_BUGREPORT "https://github.com/libimobiledevice/usbmuxd/issues"

/* Define to the home page for this package. */
#define PACKAGE_URL "https://libimobiledevice.org"

/* Define to 1 if you have the <sys/types.h> header file. */
#define HAVE_SYS_TYPES_H 1

/* Define to 1 if you have the `clock_gettime' function. */
#define HAVE_CLOCK_GETTIME 1

/* Define to 1 if you have the `localtime_r' function. */
#define HAVE_LOCALTIME_R 1

/* Define to 1 if you have the `strlcpy' function. */
#define HAVE_STRLCPY 1

/* Define to 1 if you have the `strlcat' function. */
#define HAVE_STRLCAT 1

/* Define to 1 if libimobiledevice is available. */
#define HAVE_LIBIMOBILEDEVICE 1

/* Define to 1 if idevice_connection_type is defined in libimobiledevice.h */
#define HAVE_ENUM_IDEVICE_CONNECTION_TYPE 1

#endif /* CONFIG_H */
