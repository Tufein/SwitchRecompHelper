package com.tufein.switchrecomphelper.core

/** The input is not a valid/supported dump, or a required key is missing. Message is user-presentable. */
class SwitchFormatException(message: String) : Exception(message)

/** Thrown when the user cancels a running extraction. */
class ExtractionCancelledException : Exception("Cancelled")
