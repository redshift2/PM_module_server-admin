package serveradmin

import grails.gorm.transactions.Transactional
import groovy.json.JsonSlurper
import java.nio.file.*
import java.security.MessageDigest

@Transactional
class WorkerDispatchService {
    def grailsApplication
    static final long MAX_RESULT = 100L * 1024L * 1024L

    private boolean matches(RemoteWorker worker, String type) {
        def caps = new JsonSlurper().parseText(worker.capabilities ?: '[]')
        caps.contains('ALL_JOBS') || caps.contains(type)
    }

    /** Serialized claiming prevents two concurrent polls from assigning the same job. */
    synchronized Map claim(RemoteWorker worker) {
        if (worker.status != 'APPROVED') throw new SecurityException('Not approved')
        int active = ServerJob.countByAssignedWorkerIdAndStatusInList(worker.workerId, ['ASSIGNED','RUNNING','UPLOADING_RESULTS'])
        if (active >= worker.maxConcurrentJobs) return [job:null]
        for (ServerJob job : ServerJob.findAllByStatus('QUEUED', [sort:'dateCreated',order:'asc',max:50])) {
            if (!matches(worker, job.jobType)) continue
            WorkerScript script = WorkerScript.findByJobTypeAndEnabled(job.jobType, true, [sort:'dateCreated', order:'desc'])
            if (!script) continue
            if (!job.packageFileName) continue // No input package: leave queued, do not execute.
            job.assignedWorkerId = worker.workerId
            job.assignedScriptId = script.id
            job.status = 'ASSIGNED'
            job.startedAt = new Date()
            job.leaseUntil = new Date(System.currentTimeMillis() + 30L*60L*1000L)
            job.save(failOnError:true, flush:true)
            return [job:[id:job.id, jobType:job.jobType, scriptId:script.id,
                scriptSha256:script.sha256, scriptVersion:script.scriptVersion,
                scriptName:script.name, leaseSeconds:1800]]
        }
        [job:null]
    }

    ServerJob owned(RemoteWorker worker, Long id) {
        ServerJob j = ServerJob.get(id)
        if (!j || j.assignedWorkerId != worker.workerId || !(j.status in ['ASSIGNED','RUNNING','UPLOADING_RESULTS']))
            throw new SecurityException('Job not assigned to worker')
        if (!j.leaseUntil || j.leaseUntil.before(new Date())) throw new SecurityException('Lease expired')
        j
    }

    Path input(ServerJob job) {
        String root = System.getenv('SERVER_ADMIN_JOB_STORAGE')
        if (!root) throw new IllegalStateException('Job storage not configured')
        Path path = Paths.get(root).toAbsolutePath().normalize().resolve(job.packageFileName ?: '').normalize()
        if (!path.startsWith(Paths.get(root).toAbsolutePath().normalize()) || !Files.isRegularFile(path)) throw new IllegalArgumentException('Input unavailable')
        path
    }

    Path script(ServerJob job) {
        WorkerScript ws = WorkerScript.get(job.assignedScriptId)
        if (!ws) throw new IllegalArgumentException('Script unavailable')
        String root = System.getenv('SERVER_ADMIN_SCRIPT_STORAGE')
        if (!root) throw new IllegalStateException('Script storage not configured')
        Path path = Paths.get(root).toAbsolutePath().normalize().resolve(ws.storageFile).normalize()
        if (!path.startsWith(Paths.get(root).toAbsolutePath().normalize()) || !Files.isRegularFile(path)) throw new IllegalArgumentException('Script unavailable')
        path
    }

    void progress(RemoteWorker worker, Long id, String status, int percent, String message) {
        ServerJob job = owned(worker,id)
        if (!(status in ['RUNNING','UPLOADING_RESULTS','FAILED'])) throw new IllegalArgumentException('Invalid state')
        if (percent < 0 || percent > 100) throw new IllegalArgumentException('Invalid progress')
        job.status = status
        job.progressPercent = percent
        job.resultMessage = (message ?: '').take(2000)
        if (status == 'FAILED') job.completedAt = new Date()
        else job.leaseUntil = new Date(System.currentTimeMillis() + 30L*60L*1000L)
        job.save(failOnError:true, flush:true)
    }

    void complete(RemoteWorker worker, Long id, byte[] bytes) {
        ServerJob job = owned(worker,id)
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_RESULT) throw new IllegalArgumentException('Invalid result size')
        String root = System.getenv('SERVER_ADMIN_JOB_STORAGE')
        if (!root) throw new IllegalStateException('Job storage not configured')
        Path dir = Paths.get(root).toAbsolutePath().normalize()
        Files.createDirectories(dir)
        // Validate ZIP central directory before accepting results.
        Path tmp = Files.createTempFile(dir, 'result-upload-', '.tmp')
        try {
            Files.write(tmp, bytes)
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(tmp.toFile())
            try {
                if (zip.size() == 0 || zip.size() > 10000) throw new IllegalArgumentException('Invalid ZIP entries')
                def entries = zip.entries()
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().name
                    if (!name || name.startsWith('/') || name.startsWith('\\') || name.contains('\\') || name.split('/').contains('..')) throw new IllegalArgumentException('Unsafe ZIP entry')
                }
            } finally { zip.close() }
            String name = 'result-' + job.id + '.zip'
            Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
            job.resultFileName = name
            job.status = 'COMPLETED'
            job.progressPercent = 100
            job.completedAt = new Date()
            job.resultMessage = 'Result ZIP received'
            job.save(failOnError:true,flush:true)
            if (job.jobType == 'CAD_TO_GLB') {
                try {
                    grailsApplication.mainContext.getBean('plmCadConversionService')
                        .importWorkerResult(job, dir.resolve(name))
                } catch (Exception ex) {
                    log.error('CAD_TO_GLB result import failed for job ' + job.id, ex)
                    job.status = 'FAILED'
                    job.resultMessage = ('GLB import failed: ' + ex.message).take(2000)
                    job.save(failOnError:true,flush:true)
                }
            }
        } finally { Files.deleteIfExists(tmp) }
    }
}
