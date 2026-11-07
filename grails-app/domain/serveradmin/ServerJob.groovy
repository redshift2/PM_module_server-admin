package serveradmin

/** A queued request, not an executable command. */
class ServerJob {
    String jobType
    String status = 'QUEUED'
    String submittedBy
    String parametersJson
    String packageFileName
    String resultMessage
    String assignedWorkerId
    Long assignedScriptId
    String resultFileName
    Integer progressPercent = 0
    Date leaseUntil
    Date dateCreated
    Date lastUpdated
    Date startedAt
    Date completedAt
    static constraints = {
        jobType blank: false, maxSize: 80
        status blank: false, maxSize: 24
        submittedBy nullable: true, maxSize: 120
        parametersJson nullable: true, maxSize: 4000
        packageFileName nullable: true, maxSize: 255
        resultMessage nullable: true, maxSize: 2000
        assignedWorkerId nullable: true, maxSize: 60
        assignedScriptId nullable: true
        resultFileName nullable: true, maxSize: 255
        progressPercent nullable: true, min: 0, max: 100
        leaseUntil nullable: true
        startedAt nullable: true
        completedAt nullable: true
    }
    static mapping = { table 'server_admin_job'; parametersJson type: 'text' }
}
