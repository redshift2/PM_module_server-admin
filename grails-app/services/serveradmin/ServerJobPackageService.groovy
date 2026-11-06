package serveradmin

import org.springframework.web.multipart.MultipartFile
import java.nio.file.*
import java.util.zip.ZipInputStream

/** Store immutable submitted ZIP payloads outside the webroot. No extraction or execution. */
class ServerJobPackageService {
    static final long MAX_SIZE = 100L * 1024L * 1024L
    String store(MultipartFile upload, Long jobId) {
        if (upload == null || upload.empty) throw new IllegalArgumentException('Select a ZIP package')
        if (upload.size > MAX_SIZE) throw new IllegalArgumentException('ZIP exceeds 100 MB')
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
            upload.inputStream.withCloseable { input -> Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING) }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        return target.fileName.toString()
    }
}
