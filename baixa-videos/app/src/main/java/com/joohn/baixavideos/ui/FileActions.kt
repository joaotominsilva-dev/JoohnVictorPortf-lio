package com.joohn.baixavideos.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.joohn.baixavideos.engine.SavedFile

object FileActions {
    fun open(context: Context, file: SavedFile) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(file.uri), file.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Nenhum app instalado abre esse tipo de arquivo.", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, "O arquivo não está mais acessível.", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, files: List<SavedFile>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { Uri.parse(it.uri) })
        val mime = files.map { it.mime }.distinct().singleOrNull() ?: "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(intent, "Compartilhar"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Nenhum app disponível para compartilhar.", Toast.LENGTH_SHORT).show()
        }
    }

    fun openLink(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Nenhum navegador encontrado.", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyText(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
    }

    fun readClipboard(context: Context): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }
}
