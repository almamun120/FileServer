// app/src/main/java/com/fileserver/app/FileHttpServer.kt
package com.fileserver.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PushbackInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FileHttpServer(
    private val context: Context,
    port: Int,
    private val password: String,
    private val logger: (String) -> Unit
) : NanoHTTPD(port) {

    data class ClipboardData(val files: List<String>, val isCut: Boolean)

    @Volatile
    private var storagePaths: Map<String, File> = emptyMap()
    private val clipboards = ConcurrentHashMap<String, ClipboardData>()
    @Volatile
    private var htmlCache: String? = null

    init {
        storagePaths = getStoragePaths()
        logger("Storage: " + (if (storagePaths.isEmpty()) "none accessible (grant 'All files access')" else storagePaths.keys.joinToString(", ")))
    }

    private fun dateFmt() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private fun getStoragePaths(): Map<String, File> {
        val paths = linkedMapOf<String, File>()
        try {
            val internal = Environment.getExternalStorageDirectory()
            if (internal.exists() && internal.canRead()) paths["Internal Storage"] = internal
            context.getExternalFilesDirs(null).forEachIndexed { index, file ->
                if (file != null && index > 0) {
                    val p = file.absolutePath
                    val i = p.indexOf("/Android/")
                    if (i > 0) {
                        val root = File(p.substring(0, i))
                        if (root.exists() && root.canRead()) paths["SD Card $index"] = root
                    }
                }
            }
        } catch (e: Exception) {
            logger("Error getting storage paths: ${e.message}")
        }
        return paths
    }

    // ================= ROUTING =================

    override fun serve(request: HTTPRequest): HTTPResponse {
        if (request.method == "OPTIONS") return HTTPResponse("204 No Content", "text/plain")
        if (!isAuthorized(request)) return unauthorized()

        val route = request.uri.substringBefore('?')
        val isApi = route.startsWith("/api/")

        // CSRF protection: a web page on another site cannot add this custom header without a
        // CORS pre-flight (which this server never approves).
        if (isApi && request.headers["x-fs"] == null) {
            return jerr("Forbidden", "403 Forbidden")
        }

        if (route != "/thumb" && route != "/preview" && route != "/api/clipboard" && route != "/api/about") {
            logger("${request.remoteAddr}  ${request.method} ${describe(request)}")
        }

        return try {
            when (route) {
                "/", "/index.html" -> serveHtml()
                "/api/about" -> apiAbout()
                "/api/list" -> apiList(request)
                "/api/delete" -> apiDelete(request)
                "/api/mkdir" -> apiMkdir(request)
                "/api/mkfile" -> apiMkfile(request)
                "/api/rename" -> apiRename(request)
                "/api/copy" -> apiCopy(request, false)
                "/api/cut" -> apiCopy(request, true)
                "/api/paste" -> apiPaste(request)
                "/api/clipboard" -> apiClipboard(request)
                "/api/clear-clipboard" -> { clipboards.remove(request.remoteAddr); jok() }
                "/api/upload" -> apiUpload(request)
                "/api/info" -> apiInfo(request)
                "/api/read" -> apiRead(request)
                "/api/save" -> apiSave(request)
                "/api/zip" -> apiZip(request)
                "/api/unzip" -> apiUnzip(request)
                "/api/search" -> apiSearch(request)
                "/download" -> serveDownload(request)
                "/preview" -> servePreview(request)
                "/thumb" -> serveThumb(request)
                else -> if (isApi) jerr("Unknown endpoint", "404 Not Found") else serveHtml()
            }
        } catch (e: Exception) {
            logger("Error: ${e.message}")
            jerr(e.message ?: "Unknown error", "500 Internal Server Error")
        }
    }

    private fun describe(request: HTTPRequest): String {
        val route = request.uri.substringBefore('?')
        val p = parseQuery(request.uri)["path"]
        return if (p != null) "$route  ${safeDecodePath(p)}" else route
    }

    private fun safeDecodePath(p: String): String = try {
        p.split('/').joinToString("/") { decSeg(it) }
    } catch (e: Exception) {
        p
    }

    // ================= AUTH =================

    private fun isAuthorized(req: HTTPRequest): Boolean {
        if (password.isEmpty()) return true
        val h = req.headers["authorization"] ?: return false
        if (!h.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = try {
            String(Base64.decode(h.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            return false
        }
        val given = decoded.substringAfter(':', "")
        return MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), password.toByteArray(Charsets.UTF_8))
    }

    private fun unauthorized(): HTTPResponse {
        return HTTPResponse.newFixedLengthResponse("401 Unauthorized", "text/plain; charset=utf-8", "Password required").apply {
            headers["WWW-Authenticate"] = "Basic realm=\"File Server\", charset=\"UTF-8\""
        }
    }

    // ================= JSON HELPERS =================

    private fun json(obj: Any, status: String = "200 OK"): HTTPResponse =
        HTTPResponse.newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString()).apply {
            headers["Cache-Control"] = "no-store"
        }

    private fun jerr(msg: String, status: String = "400 Bad Request"): HTTPResponse =
        json(JSONObject().put("error", msg), status)

    private fun jok(vararg pairs: Pair<String, Any?>): HTTPResponse {
        val o = JSONObject().put("success", true)
        pairs.forEach { o.put(it.first, it.second) }
        return json(o)
    }

    private fun bodyPaths(request: HTTPRequest): List<String> {
        val list = ArrayList<String>()
        try {
            val arr = JSONArray(request.body)
            for (i in 0 until arr.length()) list.add(arr.getString(i))
        } catch (_: Exception) {
        }
        return list
    }

    // ================= PATH HANDLING =================

    private fun encSeg(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun decSeg(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    /** Query string -> map; values are percent-decoded exactly once. */
    private fun parseQuery(uri: String): Map<String, String> {
        val map = HashMap<String, String>()
        val q = uri.substringAfter('?', "")
        if (q.isEmpty()) return map
        for (param in q.split('&')) {
            val kv = param.split('=', limit = 2)
            if (kv.size == 2) {
                map[kv[0]] = try { URLDecoder.decode(kv[1], "UTF-8") } catch (e: Exception) { kv[1] }
            }
        }
        return map
    }

    /**
     * "/Internal%20Storage/DCIM/a.jpg" -> File. Returns null for the virtual root, unknown
     * storage, ".." segments and anything that resolves outside the storage root.
     */
    private fun resolveFile(path: String): File? {
        try {
            val clean = path.trim().trim('/')
            if (clean.isEmpty()) return null
            val parts = clean.split('/')
            val root = storagePaths[decSeg(parts[0])] ?: return null
            var f: File = root
            for (i in 1 until parts.size) {
                if (parts[i].isEmpty()) continue
                val seg = decSeg(parts[i])
                if (seg == ".") continue
                if (seg == ".." || seg.contains('/') || seg.contains('\u0000')) return null
                f = File(f, seg)
            }
            val rootCanon = root.canonicalPath
            val canon = f.canonicalPath
            if (canon != rootCanon && !canon.startsWith(rootCanon + File.separator)) return null
            return f
        } catch (e: Exception) {
            return null
        }
    }

    private fun isStorageRoot(f: File): Boolean {
        val c = try { f.canonicalPath } catch (e: Exception) { return false }
        return storagePaths.values.any { try { it.canonicalPath == c } catch (e: Exception) { false } }
    }

    private fun isInside(child: File, parent: File): Boolean {
        return try {
            val c = child.canonicalPath
            val p = parent.canonicalPath
            c == p || c.startsWith(p + File.separator)
        } catch (e: Exception) {
            false
        }
    }

    private fun validName(name: String): Boolean {
        val n = name.trim()
        return n.isNotEmpty() && n != "." && n != ".." && !n.contains('/') && !n.contains('\\') &&
            !n.contains('\u0000') && n.toByteArray(Charsets.UTF_8).size <= 255
    }

    private fun getUniqueFile(file: File): File {
        if (!file.exists()) return file
        val name = file.nameWithoutExtension
        val ext = if (file.extension.isNotEmpty()) ".${file.extension}" else ""
        val parent = file.parentFile ?: return file
        var counter = 1
        var candidate: File
        do {
            candidate = File(parent, "${name}_($counter)$ext")
            counter++
        } while (candidate.exists() && counter < 10000)
        return candidate
    }

    private val extraMime = mapOf(
        "mkv" to "video/x-matroska", "opus" to "audio/ogg", "m4a" to "audio/mp4", "webm" to "video/webm",
        "json" to "application/json", "md" to "text/plain", "log" to "text/plain", "kt" to "text/plain",
        "java" to "text/plain", "yml" to "text/plain", "yaml" to "text/plain", "ini" to "text/plain",
        "csv" to "text/csv", "apk" to "application/vnd.android.package-archive", "heic" to "image/heic",
        "svg" to "image/svg+xml"
    )

    private fun getMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: extraMime[ext] ?: "application/octet-stream"
    }

    // ================= LISTING / SEARCH =================

    private fun apiAbout(): HTTPResponse {
        return json(
            JSONObject().put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                .put("auth", password.isNotEmpty())
        )
    }

    private fun fileJson(f: File, path: String): JSONObject {
        return JSONObject()
            .put("name", f.name)
            .put("path", path)
            .put("isDir", f.isDirectory)
            .put("isStorage", false)
            .put("size", if (f.isFile) f.length() else 0L)
            .put("modified", dateFmt().format(Date(f.lastModified())))
            .put("mtime", f.lastModified())
            .put("ext", f.extension.lowercase())
            .put("mime", if (f.isDirectory) "" else getMime(f.name))
    }

    private fun apiList(request: HTTPRequest): HTTPResponse {
        val params = parseQuery(request.uri)
        val path = (params["path"] ?: "/").trimEnd('/').ifEmpty { "/" }
        val sortBy = params["sort"] ?: "name"
        val desc = params["order"] == "desc"
        val showHidden = params["hidden"] == "true"
        val out = JSONArray()

        if (path == "/") {
            storagePaths = getStoragePaths()
            val fmt = dateFmt()
            storagePaths.forEach { (name, file) ->
                out.put(
                    JSONObject().put("name", name).put("path", "/" + encSeg(name))
                        .put("isDir", true).put("isStorage", true).put("size", 0L)
                        .put("modified", fmt.format(Date(file.lastModified())))
                        .put("free", file.freeSpace).put("total", file.totalSpace)
                )
            }
            return json(out)
        }

        val dir = resolveFile(path) ?: return jerr("Invalid path", "404 Not Found")
        if (!dir.exists() || !dir.isDirectory) return jerr("Folder not found", "404 Not Found")
        val all = dir.listFiles() ?: return jerr("Cannot read this folder (permission denied)", "403 Forbidden")

        val visible = all.filter { showHidden || !it.name.startsWith(".") }
        val nameCmp = compareBy<File> { it.name.lowercase() }
        val cmp: Comparator<File> = when (sortBy) {
            "size" -> compareBy<File> { if (it.isFile) it.length() else 0L }
            "date" -> compareBy<File> { it.lastModified() }
            "type" -> compareBy<File> { it.extension.lowercase() }
            else -> nameCmp
        }
        val dirCmp: Comparator<File> = if (sortBy == "date") cmp else nameCmp
        val dirs = visible.filter { it.isDirectory }.sortedWith(if (desc) dirCmp.reversed() else dirCmp)
        val files = visible.filter { !it.isDirectory }.sortedWith((if (desc) cmp.reversed() else cmp).then(nameCmp))

        for (f in dirs + files) out.put(fileJson(f, "$path/${encSeg(f.name)}"))
        return json(out)
    }

    private fun apiSearch(request: HTTPRequest): HTTPResponse {
        val params = parseQuery(request.uri)
        val basePath = (params["path"] ?: "/").trimEnd('/').ifEmpty { "/" }
        val query = params["q"] ?: ""
        val showHidden = params["hidden"] == "true"
        if (query.length < 2) return json(JSONArray())

        val results = JSONArray()
        val deadline = System.currentTimeMillis() + 10_000
        var count = 0

        fun search(dir: File, path: String, display: String, depth: Int) {
            if (depth > 10 || count >= 200 || System.currentTimeMillis() > deadline) return
            val list = try { dir.listFiles() } catch (e: Exception) { null } ?: return
            for (f in list) {
                if (count >= 200) return
                if (!showHidden && f.name.startsWith(".")) continue
                if (f.name.contains(query, ignoreCase = true)) {
                    results.put(fileJson(f, "$path/${encSeg(f.name)}").put("location", display))
                    count++
                }
                if (f.isDirectory && f.canRead()) {
                    search(f, "$path/${encSeg(f.name)}", "$display/${f.name}", depth + 1)
                }
            }
        }

        if (basePath == "/") {
            storagePaths.forEach { (name, file) -> search(file, "/" + encSeg(name), name, 0) }
        } else {
            val base = resolveFile(basePath) ?: return jerr("Invalid path", "404 Not Found")
            search(base, basePath, safeDecodePath(basePath).trim('/'), 0)
        }
        return json(results)
    }

    // ================= FILE OPERATIONS =================

    private fun apiDelete(request: HTTPRequest): HTTPResponse {
        var ok = 0
        var fail = 0
        for (p in bodyPaths(request)) {
            val f = resolveFile(p)
            if (f == null || !f.exists() || isStorageRoot(f)) { fail++; continue }
            if (if (f.isDirectory) f.deleteRecursively() else f.delete()) ok++ else fail++
        }
        if (ok == 0 && fail > 0) return jerr("Could not delete (not found / protected / permission denied)")
        return jok("deleted" to ok, "failed" to fail)
    }

    private fun apiMkdir(request: HTTPRequest): HTTPResponse {
        val path = parseQuery(request.uri)["path"] ?: return jerr("No path")
        val f = resolveFile(path) ?: return jerr("Invalid path")
        if (f.exists()) return jerr("Already exists")
        return if (f.mkdirs()) jok() else jerr("Could not create folder")
    }

    private fun apiMkfile(request: HTTPRequest): HTTPResponse {
        val path = parseQuery(request.uri)["path"] ?: return jerr("No path")
        val f = resolveFile(path) ?: return jerr("Invalid path")
        if (f.exists()) return jerr("A file with this name already exists")
        return try {
            f.parentFile?.mkdirs()
            f.writeBytes(request.bodyBytes)
            jok()
        } catch (e: Exception) {
            jerr(e.message ?: "Failed")
        }
    }

    private fun apiSave(request: HTTPRequest): HTTPResponse {
        val path = parseQuery(request.uri)["path"] ?: return jerr("No path")
        val f = resolveFile(path) ?: return jerr("Invalid path")
        if (!f.isFile) return jerr("File not found", "404 Not Found")
        // Without Content-Length we cannot tell "empty file" from "body was not received".
        if (!request.headers.containsKey("content-length")) return jerr("Content-Length required", "411 Length Required")
        return try {
            f.writeBytes(request.bodyBytes)
            jok()
        } catch (e: Exception) {
            jerr(e.message ?: "Save failed")
        }
    }

    private fun apiRename(request: HTTPRequest): HTTPResponse {
        val params = parseQuery(request.uri)
        val path = params["path"] ?: return jerr("No path")
        val newName = (params["name"] ?: return jerr("No name")).trim()
        val f = resolveFile(path) ?: return jerr("Invalid path")
        if (isStorageRoot(f)) return jerr("Cannot rename a storage root")
        if (!validName(newName)) return jerr("Invalid name")
        val target = File(f.parentFile, newName)
        if (target.exists() && !target.name.equals(f.name, ignoreCase = false)) return jerr("A file named \"$newName\" already exists")
        if (target.exists()) return jok()
        return if (f.renameTo(target)) jok() else jerr("Rename failed")
    }

    private fun apiCopy(request: HTTPRequest, cut: Boolean): HTTPResponse {
        val paths = bodyPaths(request).filter { p -> resolveFile(p)?.let { !isStorageRoot(it) } == true }
        if (paths.isEmpty()) return jerr("Nothing to copy")
        clipboards[request.remoteAddr] = ClipboardData(paths, cut)
        return jok("count" to paths.size, "cut" to cut)
    }

    private fun apiClipboard(request: HTTPRequest): HTTPResponse {
        val cb = clipboards[request.remoteAddr]
        val o = JSONObject()
        if (cb != null) o.put("hasData", true).put("count", cb.files.size).put("cut", cb.isCut) else o.put("hasData", false)
        return json(o)
    }

    private fun apiPaste(request: HTTPRequest): HTTPResponse {
        val destPath = parseQuery(request.uri)["path"] ?: return jerr("No path")
        val cb = clipboards[request.remoteAddr] ?: return jerr("Clipboard is empty")
        val destDir = resolveFile(destPath) ?: return jerr("Invalid destination")
        if (!destDir.isDirectory) return jerr("Destination is not a folder")

        var ok = 0
        var skipped = 0
        for (src in cb.files) {
            val srcFile = resolveFile(src)
            if (srcFile == null || !srcFile.exists()) { skipped++; continue }
            // never copy a folder into itself
            if (srcFile.isDirectory && isInside(destDir, srcFile)) { skipped++; continue }
            // moving into the folder it is already in is a no-op
            if (cb.isCut && srcFile.parentFile?.canonicalPath == destDir.canonicalPath) { ok++; continue }
            val dest = getUniqueFile(File(destDir, srcFile.name))
            try {
                if (cb.isCut) {
                    if (!srcFile.renameTo(dest)) {
                        copyRecursive(srcFile, dest)
                        srcFile.deleteRecursively()
                    }
                } else {
                    copyRecursive(srcFile, dest)
                }
                ok++
            } catch (e: Exception) {
                logger("Paste error: ${e.message}")
                skipped++
            }
        }
        if (cb.isCut) clipboards.remove(request.remoteAddr)
        return jok("pasted" to ok, "skipped" to skipped)
    }

    private fun copyRecursive(src: File, dest: File) {
        if (src.isDirectory) {
            dest.mkdirs()
            src.listFiles()?.forEach { copyRecursive(it, File(dest, it.name)) }
        } else {
            dest.parentFile?.mkdirs()
            src.inputStream().buffered(131072).use { input ->
                dest.outputStream().buffered(131072).use { output -> input.copyTo(output, 131072) }
            }
        }
    }

    // ================= INFO / READ =================

    private fun apiInfo(request: HTTPRequest): HTTPResponse {
        val path = parseQuery(request.uri)["path"] ?: return jerr("No path")
        val f = resolveFile(path) ?: return jerr("Invalid path")
        if (!f.exists()) return jerr("Not found", "404 Not Found")

        val o = JSONObject()
            .put("name", f.name).put("path", f.absolutePath).put("isDir", f.isDirectory)
            .put("modified", dateFmt().format(Date(f.lastModified())))
            .put("readable", f.canRead()).put("writable", f.canWrite())

        if (f.isFile) {
            o.put("size", f.length())
            o.put("mime", getMime(f.name))
            if (f.length() < 50L * 1024 * 1024) {
                try {
                    val md5 = MessageDigest.getInstance("MD5")
                    f.inputStream().use { ins ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = ins.read(buf)
                            if (n == -1) break
                            md5.update(buf, 0, n)
                        }
                    }
                    o.put("md5", md5.digest().joinToString("") { "%02x".format(it) })
                } catch (_: Exception) {
                }
            }
        } else {
            val deadline = System.currentTimeMillis() + 4000
            val truncated = BooleanArray(1)
            o.put("size", dirSize(f, deadline, truncated))
            o.put("approx", truncated[0])
            var files = 0
            var dirs = 0
            f.listFiles()?.forEach { if (it.isFile) files++ else dirs++ }
            o.put("files", files).put("dirs", dirs)
        }
        return json(o)
    }

    private fun dirSize(dir: File, deadline: Long, truncated: BooleanArray): Long {
        var size = 0L
        val list = try { dir.listFiles() } catch (e: Exception) { null } ?: return 0L
        for (child in list) {
            if (System.currentTimeMillis() > deadline) { truncated[0] = true; return size }
            size += if (child.isFile) child.length() else dirSize(child, deadline, truncated)
        }
        return size
    }

    private fun apiRead(request: HTTPRequest): HTTPResponse {
        val path = parseQuery(request.uri)["path"] ?: return jerr("No path")
        val f = resolveFile(path) ?: return jerr("Invalid path")
        if (!f.isFile) return jerr("Not found", "404 Not Found")
        if (f.length() > 5L * 1024 * 1024) return jerr("File too large to preview/edit (max 5MB)", "413 Payload Too Large")
        return try {
            jok("content" to f.readText())
        } catch (e: Exception) {
            jerr(e.message ?: "Read failed")
        }
    }

    // ================= ZIP / UNZIP =================

    private fun apiZip(request: HTTPRequest): HTTPResponse {
        val destPath = parseQuery(request.uri)["dest"] ?: return jerr("No destination")
        var destFile = resolveFile(destPath) ?: return jerr("Invalid destination")
        if (!destFile.name.lowercase().endsWith(".zip")) destFile = File(destFile.parentFile, destFile.name + ".zip")
        destFile = getUniqueFile(destFile)
        val sources = bodyPaths(request).mapNotNull { resolveFile(it) }.filter { it.exists() && !isStorageRoot(it) }
        if (sources.isEmpty()) return jerr("Nothing to zip")
        val destCanon = destFile.canonicalPath

        return try {
            destFile.parentFile?.mkdirs()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(destFile), 131072)).use { zos ->
                sources.forEach { addToZip(zos, it, it.name, destCanon) }
            }
            jok("name" to destFile.name)
        } catch (e: Exception) {
            try { destFile.delete() } catch (_: Exception) {}
            jerr(e.message ?: "Zip failed")
        }
    }

    private fun addToZip(zos: ZipOutputStream, file: File, name: String, skipCanonical: String?) {
        if (skipCanonical != null && try { file.canonicalPath == skipCanonical } catch (e: Exception) { false }) return
        if (file.isDirectory) {
            zos.putNextEntry(ZipEntry("$name/"))
            zos.closeEntry()
            file.listFiles()?.forEach { addToZip(zos, it, "$name/${it.name}", skipCanonical) }
        } else if (file.canRead()) {
            zos.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
            file.inputStream().use { it.copyTo(zos, 65536) }
            zos.closeEntry()
        }
    }

    private fun apiUnzip(request: HTTPRequest): HTTPResponse {
        val params = parseQuery(request.uri)
        val zipFile = resolveFile(params["path"] ?: return jerr("No path")) ?: return jerr("Invalid zip")
        val parentDir = resolveFile(params["dest"] ?: return jerr("No destination")) ?: return jerr("Invalid destination")
        if (!zipFile.isFile) return jerr("Zip not found", "404 Not Found")

        // Extract into a new folder named after the archive, never over existing files.
        val destDir = getUniqueFile(File(parentDir, zipFile.nameWithoutExtension.ifEmpty { "extracted" }))
        val destCanon = destDir.canonicalPath
        return try {
            destDir.mkdirs()
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile), 131072)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val outFile = File(destDir, entry.name.replace('\\', '/'))
                    val outCanon = outFile.canonicalPath
                    if (outCanon != destCanon && !outCanon.startsWith(destCanon + File.separator)) {
                        zis.closeEntry()
                        continue // zip-slip attempt
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().buffered(131072).use { zis.copyTo(it, 65536) }
                    }
                    zis.closeEntry()
                }
            }
            jok("folder" to destDir.name)
        } catch (e: Exception) {
            jerr(e.message ?: "Unzip failed")
        }
    }

    // ================= DOWNLOAD / PREVIEW / THUMB =================

    private fun asciiName(name: String) =
        name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")

    private fun contentDisposition(kind: String, name: String) =
        "$kind; filename=\"${asciiName(name)}\"; filename*=UTF-8''${encSeg(name)}"

    private class BoundedFileStream(private val src: FileInputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = src.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun close() = src.close()
    }

    private fun serveFile(request: HTTPRequest, f: File, download: Boolean): HTTPResponse {
        val mime = getMime(f.name)
        val len = f.length()
        var start = 0L
        var end = len - 1
        var partial = false

        val range = request.headers["range"]
        if (range != null && len > 0 && range.startsWith("bytes=", ignoreCase = true)) {
            val spec = range.substring(6).substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val a = spec.substring(0, dash).trim()
                val b = spec.substring(dash + 1).trim()
                try {
                    if (a.isEmpty()) {
                        val n = b.toLong()
                        start = maxOf(0L, len - n)
                        end = len - 1
                    } else {
                        start = a.toLong()
                        end = if (b.isEmpty()) len - 1 else minOf(b.toLong(), len - 1)
                    }
                    partial = true
                } catch (e: NumberFormatException) {
                    partial = false
                }
            }
            if (partial && (start > end || start >= len)) {
                return HTTPResponse("416 Range Not Satisfiable", "text/plain").apply {
                    headers["Content-Range"] = "bytes */$len"
                    headers["Content-Length"] = "0"
                }
            }
        }

        val fis = FileInputStream(f)
        if (start > 0) fis.channel.position(start)
        val count = if (len == 0L) 0L else end - start + 1
        val resp = HTTPResponse(if (partial) "206 Partial Content" else "200 OK", mime).apply {
            data = BoundedFileStream(fis, count)
            headers["Content-Length"] = count.toString()
            headers["Accept-Ranges"] = "bytes"
            if (partial) headers["Content-Range"] = "bytes $start-$end/$len"
        }
        if (download) {
            resp.headers["Content-Disposition"] = contentDisposition("attachment", f.name)
        } else if (mime == "text/html" || mime == "image/svg+xml" || mime.contains("xml")) {
            // stored HTML/SVG must never run scripts with access to this server's origin
            resp.headers["Content-Security-Policy"] = "sandbox"
        }
        return resp
    }

    private fun serveFolderZip(dir: File): HTTPResponse {
        val pin = PipedInputStream(256 * 1024)
        val pout = PipedOutputStream(pin)
        Thread({
            try {
                ZipOutputStream(BufferedOutputStream(pout, 65536)).use { zos -> addToZip(zos, dir, dir.name, null) }
            } catch (e: Exception) {
                try { pout.close() } catch (_: Exception) {}
            }
        }, "zip-stream").start()
        return HTTPResponse("200 OK", "application/zip").apply {
            data = pin
            headers["Content-Disposition"] = contentDisposition("attachment", dir.name + ".zip")
        }
    }

    private fun serveDownload(request: HTTPRequest): HTTPResponse {
        val path = parseQuery(request.uri)["path"] ?: return jerr("No path")
        val f = resolveFile(path) ?: return jerr("Invalid path", "404 Not Found")
        if (!f.exists()) return jerr("Not found", "404 Not Found")
        if (f.isDirectory) return serveFolderZip(f)
        return serveFile(request, f, true)
    }

    private fun servePreview(request: HTTPRequest): HTTPResponse {
        val path = parseQuery(request.uri)["path"] ?: return jerr("No path")
        val f = resolveFile(path) ?: return jerr("Invalid path", "404 Not Found")
        if (!f.isFile) return jerr("Not found", "404 Not Found")
        return serveFile(request, f, false)
    }

    private fun serveThumb(request: HTTPRequest): HTTPResponse {
        val path = parseQuery(request.uri)["path"] ?: return jerr("No path")
        val f = resolveFile(path) ?: return jerr("Invalid path", "404 Not Found")
        if (!f.isFile || !getMime(f.name).startsWith("image/")) return jerr("Not an image", "404 Not Found")

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            val minSide = minOf(bounds.outWidth, bounds.outHeight)
            while (minSide / (sample * 2) >= 200) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return jerr("Cannot decode", "415 Unsupported Media Type")
            val thumb = ThumbnailUtils.extractThumbnail(bmp, 160, 160) // centre-crop, keeps aspect ratio
            val baos = ByteArrayOutputStream()
            thumb.compress(Bitmap.CompressFormat.JPEG, 70, baos)
            if (thumb !== bmp) bmp.recycle()
            thumb.recycle()
            val bytes = baos.toByteArray()
            HTTPResponse.newFixedLengthResponse("200 OK", "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
                headers["Cache-Control"] = "private, max-age=600"
            }
        } catch (e: OutOfMemoryError) {
            jerr("Image too large", "413 Payload Too Large")
        } catch (e: Exception) {
            jerr(e.message ?: "Error", "500 Internal Server Error")
        }
    }

    private fun serveHtml(): HTTPResponse {
        var html = htmlCache
        if (html == null) {
            html = context.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            htmlCache = html
        }
        return HTTPResponse.newFixedLengthResponse("200 OK", "text/html; charset=utf-8", html).apply {
            headers["Cache-Control"] = "no-cache"
        }
    }

    // ================= UPLOAD (direct streaming) =================

    private fun apiUpload(request: HTTPRequest): HTTPResponse {
        val destPath = parseQuery(request.uri)["path"] ?: return jerr("No destination")
        val destDir = resolveFile(destPath) ?: return jerr("Invalid destination folder")

        if (!destDir.exists() && !destDir.mkdirs()) return jerr("Cannot create folder")
        if (!destDir.isDirectory) return jerr("Destination is not a folder")
        if (!destDir.canWrite()) return jerr("Cannot write to this folder", "403 Forbidden")

        val contentType = request.headers["content-type"] ?: ""
        if (!contentType.contains("multipart/form-data", ignoreCase = true)) return jerr("Expected multipart/form-data")
        val boundary = extractBoundary(contentType) ?: return jerr("No multipart boundary")
        val raw = request.inputStream ?: return jerr("No data received")

        val uploaded = ArrayList<String>()
        val errors = ArrayList<String>()
        try {
            streamMultipart(PushbackInputStream(raw, UPLOAD_BUF), boundary, destDir, uploaded, errors)
        } catch (e: Exception) {
            logger("Upload error: ${e.message}")
            return jerr("Upload failed: ${e.message ?: "unknown"}", "500 Internal Server Error")
        }

        if (uploaded.isNotEmpty()) {
            try {
                MediaScannerConnection.scanFile(context, uploaded.map { File(destDir, it).absolutePath }.toTypedArray(), null, null)
            } catch (_: Exception) {
            }
        }
        val o = JSONObject().put("success", errors.isEmpty() || uploaded.isNotEmpty())
            .put("uploaded", uploaded.size).put("failed", errors.size)
            .put("files", JSONArray(uploaded)).put("errors", JSONArray(errors))
        return json(o)
    }

    private val UPLOAD_BUF = 256 * 1024

    private fun streamMultipart(
        input: PushbackInputStream,
        boundary: String,
        destDir: File,
        uploaded: MutableList<String>,
        errors: MutableList<String>
    ) {
        val boundaryBytes = "--$boundary".toByteArray(Charsets.UTF_8)
        val buf = ByteArray(UPLOAD_BUF)

        skipToBoundary(input, boundaryBytes)

        while (true) {
            val first = readLineFromStream(input)
            if (first == null || first.startsWith("--")) break

            val headers = StringBuilder()
            while (true) {
                val line = readLineFromStream(input)
                if (line.isNullOrEmpty()) break
                headers.append(line).append("\r\n")
            }

            val filename = extractFilename(headers.toString())
            if (filename.isNullOrEmpty()) {
                skipToBoundary(input, boundaryBytes)
                continue
            }
            val safe = sanitizeFilename(filename)
            if (safe.isEmpty()) {
                errors.add("$filename: invalid file name")
                skipToBoundary(input, boundaryBytes)
                continue
            }

            val destFile = getUniqueFile(File(destDir, safe))
            try {
                FileOutputStream(destFile).use { fos ->
                    BufferedOutputStream(fos, 262144).use { bos -> streamContentToBoundary(input, bos, boundaryBytes, buf) }
                }
                uploaded.add(destFile.name)
                logger("Uploaded: ${destFile.name} (${destFile.length()} bytes)")
            } catch (e: Exception) {
                errors.add("$filename: ${e.message}")
                logger("Error saving $filename: ${e.message}")
                try { destFile.delete() } catch (_: Exception) {}
                if (e is IOException && e.message == "Upload interrupted") throw e
            }
        }
    }

    private fun readLineFromStream(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) {
                val bytes = buffer.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.UTF_8)
            }
            buffer.write(b)
            if (buffer.size() > 16 * 1024) throw IOException("Multipart header too long")
        }
    }

    /** Consumes bytes up to and including the next occurrence of [boundary]. */
    private fun skipToBoundary(input: InputStream, boundary: ByteArray) {
        val window = ByteArray(boundary.size)
        var filled = 0
        while (true) {
            val b = input.read()
            if (b == -1) return
            if (filled < boundary.size) {
                window[filled++] = b.toByte()
            } else {
                System.arraycopy(window, 1, window, 0, boundary.size - 1)
                window[boundary.size - 1] = b.toByte()
            }
            if (filled == boundary.size && window.contentEquals(boundary)) return
        }
    }

    /**
     * Copies file content to [output] until "\r\n--boundary". Works on whole buffers (fast); bytes
     * read beyond the boundary are pushed back so the caller can continue parsing.
     */
    private fun streamContentToBoundary(input: PushbackInputStream, output: OutputStream, boundary: ByteArray, buf: ByteArray) {
        val pattern = ByteArray(2 + boundary.size)
        pattern[0] = '\r'.code.toByte()
        pattern[1] = '\n'.code.toByte()
        System.arraycopy(boundary, 0, pattern, 2, boundary.size)
        val plen = pattern.size
        var filled = 0

        while (true) {
            val n = input.read(buf, filled, buf.size - filled)
            if (n == -1) throw IOException("Upload interrupted")
            filled += n

            val idx = indexOf(buf, filled, pattern)
            if (idx >= 0) {
                output.write(buf, 0, idx)
                val after = idx + plen
                val leftover = filled - after
                if (leftover > 0) input.unread(buf, after, leftover)
                return
            }
            val keep = minOf(plen - 1, filled)
            val writeLen = filled - keep
            if (writeLen > 0) {
                output.write(buf, 0, writeLen)
                System.arraycopy(buf, writeLen, buf, 0, keep)
            }
            filled = keep
        }
    }

    private fun indexOf(data: ByteArray, length: Int, pattern: ByteArray): Int {
        val last = length - pattern.size
        val first = pattern[0]
        var i = 0
        while (i <= last) {
            if (data[i] == first) {
                var j = 1
                while (j < pattern.size && data[i + j] == pattern[j]) j++
                if (j == pattern.size) return i
            }
            i++
        }
        return -1
    }

    private fun extractBoundary(contentType: String): String? {
        for (part in contentType.split(";")) {
            val t = part.trim()
            if (t.lowercase().startsWith("boundary=")) {
                var b = t.substring(9)
                if (b.length >= 2 && b.startsWith("\"") && b.endsWith("\"")) b = b.substring(1, b.length - 1)
                return b
            }
        }
        return null
    }

    private fun extractFilename(headers: String): String? {
        Regex("""filename\*\s*=\s*[^']*'[^']*'([^;\r\n]+)""", RegexOption.IGNORE_CASE).find(headers)?.let {
            return try { URLDecoder.decode(it.groupValues[1].trim().replace("+", "%2B"), "UTF-8") } catch (e: Exception) { it.groupValues[1].trim() }
        }
        Regex("""filename\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(headers)?.let { return it.groupValues[1].trim() }
        Regex("""filename\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE).find(headers)?.let { return it.groupValues[1].trim() }
        return null
    }

    private fun sanitizeFilename(filename: String): String {
        val name = filename.substringAfterLast('/').substringAfterLast('\\')
        return name.replace("\u0000", "")
            .replace(Regex("[:*?\"<>|]"), "_")
            .trim()
            .takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: ""
    }
}
