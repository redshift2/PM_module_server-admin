package serveradmin

import grails.gorm.transactions.Transactional
import groovy.json.JsonOutput

/** Submission API. A separate worker will be added before jobs execute. */
@Transactional
class ServerJobService {
    static final Set<String> SUPPORTED_TYPES = ['MAINTENANCE_REVIEW', 'EVENT_REPORT', 'REMOTE_PACKAGE'].asImmutable()

    ServerJob submit(String jobType, String submittedBy, Map params = [:]) {
        if (!(jobType in SUPPORTED_TYPES)) throw new IllegalArgumentException('Unsupported job type')
        String json = JsonOutput.toJson(params ?: [:])
        if (json.length() > 4000) throw new IllegalArgumentException('Job parameters too long')
        new ServerJob(jobType: jobType, submittedBy: submittedBy, parametersJson: json,
            status: 'QUEUED').save(failOnError: true)
    }

    @Transactional(readOnly = true)
    List<ServerJob> recent(int limit = 100) {
        ServerJob.list(max: Math.min(Math.max(limit, 1), 200), sort: 'dateCreated', order: 'desc')
    }
    @Transactional
    void deleteJob(Long jobId) {
        ServerJob job = ServerJob.get(jobId)

        if (job != null) {
            job.delete(flush: true, failOnError: true)
    }
}
}
