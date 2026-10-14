package com.ridetrack.app.studio

import java.io.File

/**
 * Writes [text] so the file is either the old version or the new one, never half of it (the
 * app killed or the phone switched off mid-save): written beside it first, then renamed over it.
 */
internal fun File.writeAtomically(text: String) {
    parentFile?.mkdirs()
    // Its own name, so two saves at once (quick edits) never write into the same file.
    val tmp = File(parentFile, "$name.${System.nanoTime()}.tmp")
    try {
        tmp.outputStream().use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
    } catch (e: Exception) {
        // Phone full or the like: the old file stays as it was.
        tmp.delete()
        throw e
    }
    if (!tmp.renameTo(this)) {
        // Some filesystems won't rename over a file: replace it.
        delete()
        if (!tmp.renameTo(this)) { tmp.copyTo(this, overwrite = true); tmp.delete() }
    }
}
