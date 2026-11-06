package com.tufein.switchrecomphelper.core

/** The input is not a valid/supported dump, or a required key is missing. Message is user-presentable. */
open class SwitchFormatException(message: String) : Exception(message)

/** A section we cannot read (e.g. a BKTR patch section); the rest of the title can still be extracted. */
class UnsupportedSectionException(message: String) : SwitchFormatException(message)

/** Thrown when the user cancels a running extraction. */
class ExtractionCancelledException : Exception("Cancelled")
