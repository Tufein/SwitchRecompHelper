package com.tufein.switchrecomphelper

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.tufein.switchrecomphelper.core.OutputSink
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Writes into a folder picked with the Storage Access Framework.
 * Everything goes into a freshly created [runFolderName] subfolder (the provider renames on clash),
 * so we never have to look for or overwrite existing documents.
 */
class SafSink(
    private val resolver: ContentResolver,
    treeUri: Uri,
    runFolderName: String,
) : OutputSink {
    private val dirs = HashMap<String, Uri>()

    init {
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        dirs[""] = createDir(root, runFolderName)
    }

    private fun createDir(parent: Uri, name: String): Uri =
        DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Cannot create folder '$name'")

    private fun ensureDir(path: String): Uri = dirs.getOrPut(path) {
        val slash = path.lastIndexOf('/')
        val parent = ensureDir(if (slash < 0) "" else path.substring(0, slash))
        createDir(parent, path.substring(slash + 1))
    }

    override fun create(relativePath: String): OutputStream {
        val slash = relativePath.lastIndexOf('/')
        val parent = ensureDir(if (slash < 0) "" else relativePath.substring(0, slash))
        val uri = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", relativePath.substring(slash + 1))
            ?: throw IOException("Cannot create '$relativePath'")
        val out = resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot write '$relativePath'")
        return BufferedOutputStream(out, 1 shl 20)
    }
}
