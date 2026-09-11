package dev.woms.mumdroid.data

/**
 * Thrown when a PKCS#12 file cannot be opened with the supplied password,
 * indicating that the caller should ask the user for the correct password.
 */
class WrongPasswordException(message: String = "Wrong password") : Exception(message)

/**
 * Thrown when a PKCS#12 file is truncated, corrupted or not a PKCS#12 file at
 * all. Re-entering the password cannot help; the caller should tell the user
 * to pick another file instead of prompting for a password again.
 */
class CertificateFileCorruptException(message: String) : Exception(message)
