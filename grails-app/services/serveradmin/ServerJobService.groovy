package serveradmin

import grails.gorm.transactions.Transactional
import groovy.json.JsonOutput
import org.springframework.transaction.annotation.Propagation

/** Submission API. A separate worker will be added before jobs execute. */
@Transactional
class ServerJobService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ServerJob submit(String jobType, String submittedBy, Map params = [:], String routeData = null) {
        if (!WorkerScript.findByJobTypeAndEnabled(jobType, true)) throw new IllegalArgumentException('No enabled worker script for job type: ' + jobType)
        String route = JobResultRoute.validate(routeData ?: JobResultRoute.legacyDefault(jobType))
        String json = JsonOutput.toJson(params ?: [:])
        if (json.length() > 4000) throw new IllegalArgumentException('Job parameters too long')
        new ServerJob(jobType: jobType, routeData: route, submittedBy: submittedBy, parametersJson: json,
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
    /** Remove all successfully completed jobs, not failed or active jobs. */
    @Transactional
    int deleteCompletedJobs() {
        List<Long> ids = ServerJob.executeQuery(
            'select j.id from ServerJob j where j.status = :status',
            [status: 'COMPLETED']) as List<Long>
        int count = 0
        ids.each { Long id ->
            ServerJob job = ServerJob.get(id)
            if (job != null && job.status == 'COMPLETED') {
                job.delete(failOnError: true)
                count++
            }
        }
        ServerJob.withSession { it.flush() }
        // Stored ZIP files are intentionally retained, consistent with deleteJob().
        return count
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
