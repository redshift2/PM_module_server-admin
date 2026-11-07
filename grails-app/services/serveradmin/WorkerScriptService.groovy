package serveradmin

import grails.gorm.transactions.Transactional
import org.springframework.web.multipart.MultipartFile
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID

@Transactional
class WorkerScriptService {
    WorkerScript upload(MultipartFile file, String name, String version, String jobType, String username) {
        if (!file || file.empty || file.size > 1024L * 1024L) throw new IllegalArgumentException('Python script required (max 1 MB)')
        if (!(name ==~ /[A-Za-z0-9_.-]{1,120}/) || !(version ==~ /[A-Za-z0-9_.-]{1,50}/)) throw new IllegalArgumentException('Invalid script name/version')
        if (!(jobType ==~ /[A-Z0-9_]{2,80}/)) throw new IllegalArgumentException('Invalid job type')
        String root = System.getenv('SERVER_ADMIN_SCRIPT_STORAGE')
        if (!root) throw new IllegalStateException('SERVER_ADMIN_SCRIPT_STORAGE must be configured')
        byte[] bytes = file.bytes
        String hash = MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()
        Path dir = Paths.get(root).toAbsolutePath().normalize()
        Files.createDirectories(dir)
        String stored = UUID.randomUUID().toString() + '.py'
        Path target = dir.resolve(stored)
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW)
        try {
            return new WorkerScript(name:name, scriptVersion:version, jobType:jobType,
                sha256:hash, storageFile:stored, uploadedBy:username, enabled:false).save(failOnError:true,flush:true)
        } catch(Exception e) { Files.deleteIfExists(target); throw e }
    }
    void setEnabled(Long id, boolean enabled) {
        WorkerScript script = WorkerScript.get(id)
        if (!script) throw new IllegalArgumentException('Script not found')
        script.enabled = enabled
        script.save(failOnError:true,flush:true)
    }
    void deleteScript(Long id) {
        WorkerScript script = WorkerScript.get(id)
        if (!script) throw new IllegalArgumentException('Script not found')
        if (ServerJob.countByAssignedScriptId(id) > 0)
            throw new IllegalStateException('Script is referenced by job history. Disable it instead.')
        // Delete DB record first; a missing file is harmless, but a failed DB delete
        // must not remove the script needed by the dispatcher.
        String storageFile = script.storageFile
        script.delete(flush:true)
        String root = System.getenv('SERVER_ADMIN_SCRIPT_STORAGE')
        if (root && storageFile ==~ /[0-9a-fA-F-]{36}\.py/) {
            // File cleanup is best-effort. The database record is authoritative.
            try { Files.deleteIfExists(Paths.get(root).toAbsolutePath().normalize().resolve(storageFile)) }
            catch (Exception ignored) { log.warn('Unable to remove script file ' + storageFile) }
        }
    }
}
