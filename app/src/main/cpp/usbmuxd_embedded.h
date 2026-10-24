/*
 * usbmuxd_embedded.h
 *
 * Header for embedded usbmuxd lifecycle and configuration.
 */

#ifndef USBMUXD_EMBEDDED_H
#define USBMUXD_EMBEDDED_H

extern volatile int should_exit;

int usbmuxd_embedded_start(const char *abstract_socket_name, const char *storage_dir);
void usbmuxd_embedded_stop(void);
const char *usbmuxd_get_storage_dir(void);

#endif /* USBMUXD_EMBEDDED_H */
