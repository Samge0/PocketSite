package com.samge.pocketsite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * SAF picker contracts that open at a remembered location.
 *
 * The hint must be a DIRECTORY-ish document uri for DocumentsUI to navigate there.
 * A raw file document uri usually works too (it focuses the parent), but some
 * DocumentsUI versions ignore file-form hints — so we normalize to the parent
 * directory document uri before attaching EXTRA_INITIAL_URI.
 */
private fun dirHint(raw: String?): Uri? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val uri = Uri.parse(raw)
        val docId = DocumentsContract.getDocumentId(uri)          // e.g. "primary:Download/sp.zip"
        val authority = uri.authority ?: return@runCatching null  // externalstorage
        val split = docId.indexOf(':')
        if (split <= 0) return@runCatching null
        val root = docId.substring(0, split)                      // "primary"
        val path = docId.substring(split + 1)                     // "Download/sp.zip"
        val parent = path.substringBeforeLast('/', "")            // "Download"
        val parentDocId = "$root:$parent"
        DocumentsContract.buildDocumentUri(authority, parentDocId)
    }.getOrNull()
}

/** OpenDocument that opens at the parent of [lastFileUri] (if remembered). */
class OpenDocumentAt(lastFileUri: String?) : ActivityResultContracts.OpenDocument() {
    private val hint = dirHint(lastFileUri)

    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        hint?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        return intent
    }
}

/** CreateDocument that opens at the parent of [lastFileUri] (if remembered). */
class CreateDocumentAt(lastFileUri: String?, mimeType: String) :
    ActivityResultContracts.CreateDocument(mimeType) {
    private val hint = dirHint(lastFileUri)

    override fun createIntent(context: Context, input: String): Intent {
        val intent = super.createIntent(context, input)
        hint?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        return intent
    }
}
