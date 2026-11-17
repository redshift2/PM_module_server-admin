package serveradmin

import org.springframework.web.multipart.MultipartFile
import java.nio.file.*
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

/** Store immutable submitted ZIP payloads outside the webroot. No extraction or execution. */
class ServerJobPackageService {
    long getMaxSize() {
        String configured = System.getenv('MAX_UPLOAD_SIZE') ?: '1073741824'
        long limit = Long.parseLong(configured)
        if (limit <= 0) throw new IllegalStateException('MAX_UPLOAD_SIZE must be positive')
        return limit
    }
    String store(MultipartFile upload, Long jobId, Map jobConfiguration = null) {
        if (upload == null || upload.empty) throw new IllegalArgumentException('Select a ZIP package')
        if (upload.size > maxSize) throw new IllegalArgumentException('ZIP exceeds server upload limit')
        String base = System.getenv('SERVER_ADMIN_JOB_STORAGE')
        if (!base?.trim()) throw new IllegalStateException('SERVER_ADMIN_JOB_STORAGE must be configured')
        Path directory = Paths.get(base).toAbsolutePath().normalize()
        Files.createDirectories(directory)
        Path target = directory.resolve('job-' + jobId + '.zip')
        // Validate archive format and entry names without unpacking.
        int entries = 0
        ZipInputStream zis = new ZipInputStream(upload.inputStream)
        try {
            def entry
            while ((entry = zis.nextEntry) != null) {
                entries++
                if (entries > 10000) throw new IllegalArgumentException('Too many ZIP entries')
                String name = entry.name
                if (!name || name.startsWith('/') || name.startsWith('\\') || name.contains('\\') || name.split('/').contains('..'))
                    throw new IllegalArgumentException('Unsafe ZIP entry path')
                zis.closeEntry()
            }
        } finally { zis.close() }
        if (!entries) throw new IllegalArgumentException('ZIP is empty or invalid')
        Path temporary = Files.createTempFile(directory, 'upload-', '.tmp')
        try {
            if (jobConfiguration == null) {
                upload.inputStream.withCloseable { input -> Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING) }
            } else {
                // Deliver job options as a root-level manifest.json inside the worker input ZIP.
                // Preserve any user-supplied manifest fields while replacing server-controlled options.
                Map manifest = [:]
                java.util.zip.ZipFile source = null
                try {
                    // Multipart streams need not be repeatable; stage the original ZIP separately.
                    Path staged = Files.createTempFile(directory, 'source-', '.zip')
                    try {
                        upload.inputStream.withCloseable { input -> Files.copy(input, staged, StandardCopyOption.REPLACE_EXISTING) }
                        source = new java.util.zip.ZipFile(staged.toFile())
                        ZipEntry originalManifest = source.getEntry('manifest.json')
                        if (originalManifest != null) {
                            if (originalManifest.size < 0 || originalManifest.size > 1048576L)
                                throw new IllegalArgumentException('Existing manifest.json exceeds 1 MB')
                            String originalText = source.getInputStream(originalManifest).withCloseable { it.getText('UTF-8') }
                            def parsed = new JsonSlurper().parseText(originalText)
                            if (!(parsed instanceof Map)) throw new IllegalArgumentException('manifest.json must be a JSON object')
                            manifest.putAll(parsed as Map)
                        }
                        manifest.putAll(jobConfiguration)
                        byte[] manifestBytes = JsonOutput.toJson(manifest).getBytes('UTF-8')
                        if (manifestBytes.length > 1048576) throw new IllegalArgumentException('Job manifest exceeds 1 MB')
                        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temporary))
                        try {
                            def sourceEntries = source.entries()
                            byte[] buffer = new byte[65536]
                            while (sourceEntries.hasMoreElements()) {
                                ZipEntry entry = sourceEntries.nextElement()
                                if (entry.name == 'manifest.json') continue
                                ZipEntry copy = new ZipEntry(entry.name)
                                out.putNextEntry(copy)
                                if (!entry.isDirectory()) {
                                    source.getInputStream(entry).withCloseable { input ->
                                        int n
                                        while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n)
                                    }
                                }
                                out.closeEntry()
                            }
                            out.putNextEntry(new ZipEntry('manifest.json'))
                            out.write(manifestBytes)
                            out.closeEntry()
                        } finally { out.close() }
                    } finally {
                        if (source != null) { source.close(); source = null }
                        Files.deleteIfExists(staged)
                    }
                } finally { if (source != null) source.close() }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        return target.fileName.toString()
    }
}
