/* Build configuration for the vendored speexdsp preprocessor on Android.
 *
 * The upstream project generates this file from configure.ac; we provide a
 * static equivalent matching the desktop Mumble client's build:
 * floating-point build using the smallft FFT backend.
 */

#ifndef __SPEEXDSP_CONFIG_H__
#define __SPEEXDSP_CONFIG_H__

/* C99 variable-length arrays instead of alloca(). */
#define VAR_ARRAYS 1

/* Floating-point build (desktop clients also use FLOATING_POINT). */
#define FLOATING_POINT 1

/* Use the smallft FFT backend (pairs with FLOATING_POINT upstream). */
#define USE_SMALLFT 1

#define EXPORT

#define HAVE_STDINT_H 1
#define HAVE_SYS_TYPES_H 1

#endif
