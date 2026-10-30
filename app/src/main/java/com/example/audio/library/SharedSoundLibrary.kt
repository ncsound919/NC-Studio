package com.example.audio.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.overlay.audio.library.LibraryEntry
import com.overlay.audio.library.LibraryFile
import com.overlay.audio.library.LibraryIndex
import com.overlay.audio.library.SoundLibrary
import com.overlay.audio.library.SoundLibraryLayout
import java.io.File
import org.json.JSONObject

/**
 * One shared folder for every NC music app.
 *
 * The user grants a tree once (`ACTION_OPEN_DOCUMENT_TREE`); the permission is
 * persisted, so NC Studio, NC Phone Beat and any other app that is pointed at
 * the same tree read the identical files without copying them. A scan reads
 * metadata and the first 4 KB of each WAV for rate/channels/duration, then
 * writes the canonical `nc.sound-library.v1` index to app storage. Reopening
 * the browser is an index load, not a folder walk.
 */
class SharedSoundLibrary(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, CACHE_FILE)
    private val uriCacheFile = File(context.filesDir, URI_CACHE_FILE)
    private var uriByPath: Map<String, Uri> = loadUriCache()

    var rootName: String = SoundLibraryLayout.ROOT
        private set

    val treeUri: Uri?
        get() = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    /** Persists the tree permission across process restarts. */
    fun rememberTree(uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        true
    } catch (_: SecurityException) {
        // Some providers grant only read; retry without write before giving up.
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun loadCache(): LibraryIndex? {
        uriByPath = loadUriCache()
        if (!cacheFile.isFile) return null
        return runCatching { SoundLibrary.decode(cacheFile.readText()) }.getOrNull()
    }

    private fun loadUriCache(): Map<String, Uri> = runCatching {
        if (!uriCacheFile.isFile) emptyMap()
        else {
            val root = JSONObject(uriCacheFile.readText())
            root.keys().asSequence().associateWith { path -> Uri.parse(root.getString(path)) }
        }
    }.getOrDefault(emptyMap())

    private fun saveUriCache(map: Map<String, Uri>) {
        val root = JSONObject()
        for ((path, uri) in map) root.put(path, uri.toString())
        runCatching { uriCacheFile.writeText(root.toString()) }
    }

    fun uriFor(entry: LibraryEntry): Uri? = uriByPath[entry.path]

    /**
     * Walks the tree once. Limits are deliberate: a runaway folder must not
     * stall the picker, and the index is metadata-only so 3k files stay cheap.
     */
    fun scan(onProgress: ((Int) -> Unit)? = null): LibraryIndex? {
        val tree = treeUri ?: return null
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        rootName = queryName(tree, rootId) ?: SoundLibraryLayout.ROOT
        val files = ArrayList<LibraryFile>()
        val facts = HashMap<String, com.overlay.audio.library.WavFacts?>()
        val uris = HashMap<String, Uri>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(rootId to "")
        var visited = 0
        while (queue.isNotEmpty() && files.size < MAX_FILES) {
            val (docId, prefix) = queue.removeFirst()
            if (++visited > MAX_DOCUMENTS) break
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            context.contentResolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext() && files.size < MAX_FILES) {
                    val childId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: continue
                    val mime = cursor.getString(mimeCol).orEmpty()
                    val path = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (path.count { it == '/' } < MAX_DEPTH) queue.add(childId to path)
                        continue
                    }
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in SoundLibrary.AUDIO_EXTS) continue
                    val size = cursor.getLong(sizeCol).coerceAtLeast(0L)
                    val modified = cursor.getLong(modCol).coerceAtLeast(0L)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                    files.add(LibraryFile(path = path, name = name, sizeBytes = size, modifiedAt = modified))
                    uris[path] = docUri
                    if (ext == "wav") facts[path] = readWavFacts(docUri)
                    onProgress?.invoke(files.size)
                }
            }
        }
        val entries = files.mapNotNull { file -> SoundLibrary.entryFor(file, facts[file.path]) }
        val index = LibraryIndex(rootName, entries)
        uriByPath = uris
        saveUriCache(uris)
        runCatching { cacheFile.writeText(SoundLibrary.encode(index)) }
        return index
    }

    private fun queryName(tree: Uri, docId: String): String? = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Throwable) {
        null
    }

    private fun readWavFacts(uri: Uri): com.overlay.audio.library.WavFacts? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(HEADER_BYTES)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n <= 0) break
                read += n
            }
            SoundLibrary.readWavFacts(if (read == header.size) header else header.copyOf(read))
        }
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val PREFS = "shared_sound_library"
        private const val KEY_TREE_URI = "tree_uri"
        private const val CACHE_FILE = "shared-library.index.json"
        private const val URI_CACHE_FILE = "shared-library.uris.json"
        private const val HEADER_BYTES = 4096
        private const val MAX_FILES = 3_000
        private const val MAX_DOCUMENTS = 8_000
        private const val MAX_DEPTH = 6
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
