package serveradmin

import grails.gorm.transactions.Transactional
import groovy.json.JsonOutput
import org.springframework.transaction.annotation.Propagation

/** Submission API. A separate worker will be added before jobs execute. */
@Transactional
class ServerJobService {
    static final Set<String> SUPPORTED_TYPES = ['MAINTENANCE_REVIEW', 'EVENT_REPORT', 'REMOTE_PACKAGE', 'CAD_TO_GLB'].asImmutable()

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ServerJob submit(String jobType, String submittedBy, Map params = [:]) {
        if (!(jobType in SUPPORTED_TYPES)) throw new IllegalArgumentException('Unsupported job type')
        String json = JsonOutput.toJson(params ?: [:])
        if (json.length() > 4000) throw new IllegalArgumentException('Job parameters too long')
        new ServerJob(jobType: jobType, submittedBy: submittedBy, parametersJson: json,
            status: 'QUEUED').save(failOnError: true)
    }

    /** Persist the uploaded ZIP filename in a fresh transaction after release commit. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void setPackageFileName(Long jobId, String storedFileName) {
        if (!storedFileName?.trim()) throw new IllegalArgumentException('Stored package filename is empty')
        ServerJob job = ServerJob.get(jobId)
        if (job == null) throw new IllegalArgumentException('Job not found: ' + jobId)
        if (job.status != 'QUEUED') throw new IllegalStateException('Job is no longer queued')
        job.packageFileName = storedFileName
        job.save(failOnError: true, flush: true)
    }

    @Transactional(readOnly = true)
    List<ServerJob> recent(int limit = 100) {
        ServerJob.list(max: Math.min(Math.max(limit, 1), 200), sort: 'dateCreated', order: 'desc')
    }
    @Transactional
    void deleteJob(Long jobId) {
        ServerJob job = ServerJob.get(jobId)
        if (job == null) throw new IllegalArgumentException('Job not found')
        if (job.status in ['ASSIGNED', 'RUNNING', 'UPLOADING_RESULTS']) {
            throw new IllegalStateException('Cannot delete a job while a worker is processing it')
        }
        // Leave stored input/result files in place: the storage service has no
        // transactional deletion API. Files may be cleaned up separately.
        job.delete(flush: true, failOnError: true)
    }
}
