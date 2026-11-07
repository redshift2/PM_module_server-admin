package serveradmin

import grails.plugin.springsecurity.annotation.Secured
import org.springframework.web.multipart.MultipartFile
import org.springframework.security.core.context.SecurityContextHolder

@Secured(['ROLE_PLM_USER', 'ROLE_ADMIN'])
class ServerAdminController {
    def grailsPluginManager
    def springSecurityService
    ServerAdminMaintenanceService serverAdminMaintenanceService
    ServerAdminModuleInfoService serverAdminModuleInfoService
    ServerAdminMaterialsService serverAdminMaterialsService
    ServerEventService serverEventService
    ServerJobService serverJobService
    ServerJobPackageService serverJobPackageService
    ServerEventActionService serverEventActionService
    ServerAdminEmailService serverAdminEmailService
    RemoteWorkerService remoteWorkerService
    WorkerScriptService workerScriptService
    WorkerDispatchService workerDispatchService

    private boolean admin() {
        SecurityContextHolder.context?.authentication?.authorities?.any { it.authority == 'ROLE_ADMIN' } ?: false
    }

    def index() { renderServerInfo() }

    def serverInfo() { renderServerInfo() }

    private void renderServerInfo() {
        List<Map> modules = serverAdminModuleInfoService.installedModules()
        render(view: 'index', model: [tab: 'info', modules: modules, isAdmin: admin()])
    }

    def jobQueue() {
        render(view: 'index', model: [tab: 'jobs', isAdmin: admin(), jobs: serverJobService.recent()])
    }

    @Secured(['ROLE_ADMIN'])
    def deleteQueuedJob() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            Long jobId = params.long('id')
            if (jobId == null) throw new IllegalArgumentException('Job ID required')
            serverJobService.deleteJob(jobId)
            flash.message = 'Job #' + jobId + ' deleted.'
        } catch (Exception ex) {
            log.warn('Job deletion failed', ex)
            flash.error = 'Unable to delete job: ' + ex.message
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/jobQueue')
    }

    @Secured(['ROLE_ADMIN'])
    def submitJob() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            serverJobService.submit(params.jobType as String,
                springSecurityService.currentUser?.username as String)
            flash.message = 'Job submitted to queue.'
        } catch (Exception ex) {
            flash.error = ex.message
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/jobQueue')
    }

    @Secured(['ROLE_ADMIN'])
    def events() {
        render(view: 'index', model: [tab: 'events', isAdmin: true, events: serverEventService.recent(), rules: ServerEventRule.list(sort:'id', order:'desc'), activeJobTypes: WorkerScript.findAllByEnabled(true).collect { it.jobType }.unique().sort(), actions: serverEventActionService.recent()])
    }

    @Secured(['ROLE_ADMIN'])
    def createEventRule() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            String type = params.eventType as String
            String action = params.actionType as String
            String dest = (action == 'SUBMIT_JOB' ? (params.jobDestination ?: '') : (params.destination ?: '')) as String
            if (!(type in ServerEventService.KNOWN_TYPES)) throw new IllegalArgumentException('Unknown event type')
            if (!(action in ['LOG','EMAIL','SUBMIT_JOB'])) throw new IllegalArgumentException('Unknown action')
            if (action == 'EMAIL' && !dest.matches('^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$')) throw new IllegalArgumentException('Valid email recipient required')
            if (action == 'SUBMIT_JOB' && !(dest in WorkerScript.findAllByEnabled(true).collect { it.jobType })) throw new IllegalArgumentException('Job type requires an enabled worker script')
            new ServerEventRule(name: params.name, eventType:type, actionType:action, destination:dest).save(failOnError:true, flush:true)
            flash.message = 'Event rule saved.'
        } catch (Exception ex) { flash.error = ex.message }
        response.sendRedirect(request.contextPath + '/serverAdmin/events')
    }

    @Secured(['ROLE_ADMIN'])
    def updateEventRule() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            ServerEventRule rule = ServerEventRule.get(params.long('id'))
            if (!rule) throw new IllegalArgumentException('Rule not found')
            String type = params.eventType as String
            String action = params.actionType as String
            String dest = (action == 'SUBMIT_JOB' ? (params.jobDestination ?: '') : (params.destination ?: '')).toString().trim()
            if (!(type in ServerEventService.KNOWN_TYPES)) throw new IllegalArgumentException('Unknown event type')
            if (!(action in ['LOG','EMAIL','SUBMIT_JOB'])) throw new IllegalArgumentException('Unknown action')
            if (action == 'EMAIL' && !dest.matches('^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$')) throw new IllegalArgumentException('Valid email recipient required')
            if (action == 'SUBMIT_JOB' && !(dest in WorkerScript.findAllByEnabled(true).collect { it.jobType }))
                throw new IllegalArgumentException('Job type requires an enabled worker script')
            rule.name = params.name as String
            rule.eventType = type
            rule.actionType = action
            rule.destination = dest
            rule.enabled = params.enabled == 'true'
            rule.save(failOnError:true, flush:true)
            flash.message = 'Event rule updated.'
        } catch (Exception ex) { flash.error = ex.message }
        response.sendRedirect(request.contextPath + '/serverAdmin/events')
    }

    @Secured(['ROLE_ADMIN'])
    def deleteEventRule() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            ServerEventRule rule = ServerEventRule.get(params.long('id'))
            if (!rule) throw new IllegalArgumentException('Rule not found')
            // Keep historical event actions and their rule references intact.
            if (ServerEventAction.countByRule(rule) > 0)
                throw new IllegalStateException('Rule has event history. Disable it instead of deleting it.')
            rule.delete(flush:true)
            flash.message = 'Event rule deleted.'
        } catch (Exception ex) { flash.error = ex.message }
        response.sendRedirect(request.contextPath + '/serverAdmin/events')
    }

    @Secured(['ROLE_ADMIN'])
    def deleteWorker() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            RemoteWorker worker = RemoteWorker.get(params.long('id'))
            if (!worker) throw new IllegalArgumentException('Worker not found')
            if (ServerJob.countByAssignedWorkerIdAndStatusInList(worker.workerId,
                    ['ASSIGNED','RUNNING','IN_PROGRESS']) > 0)
                throw new IllegalStateException('Worker has active jobs. Revoke and wait for jobs to finish.')
            worker.delete(flush:true)
            flash.message = 'Worker deleted. Its credentials can register again as a new worker.'
        } catch (Exception ex) { flash.error = ex.message }
        response.sendRedirect(request.contextPath + '/serverAdmin/workers')
    }

    @Secured(['ROLE_ADMIN'])
    def deleteWorkerScript() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            workerScriptService.deleteScript(params.long('id'))
            flash.message = 'Script deleted.'
        } catch (Exception ex) { flash.error = ex.message }
        response.sendRedirect(request.contextPath + '/serverAdmin/workers')
    }

    @Secured(['ROLE_ADMIN'])
    def sendEventEmail() {
        if (request.method != 'POST') { response.sendError(405); return }
        try { serverEventActionService.dispatchEmail(params.long('id')); flash.message = 'Email sent.' }
        catch (Exception ex) { flash.error = 'Email delivery failed: ' + ex.message }
        response.sendRedirect(request.contextPath + '/serverAdmin/events')
    }

    @Secured(['ROLE_ADMIN'])
    def testEmail() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            serverAdminEmailService.send(params.to as String, 'Server Administration SMTP test', 'SMTP connection test from Server Administration.')
            flash.message = 'Test email sent.'
        } catch (Exception ex) { flash.error = 'SMTP test failed: ' + ex.message }
        response.sendRedirect(request.contextPath + '/serverAdmin/emailServer')
    }

    @Secured(['ROLE_ADMIN'])
    def submitPackageJob() {
        if (request.method != 'POST') { response.sendError(405); return }
        ServerJob job
        try {
            MultipartFile upload = request.getFile('file')
            job = serverJobService.submit('REMOTE_PACKAGE', springSecurityService.currentUser?.username as String)
            String storedFileName = serverJobPackageService.store(upload, job.id)
            serverJobService.setPackageFileName(job.id as Long, storedFileName)
            flash.message = 'ZIP job queued for an approved worker.'
        } catch (Exception ex) {
            log.error('ZIP job submission failed', ex)
            if (job?.id) {
                try { serverJobService.deleteJob(job.id as Long) }
                catch (Exception cleanupEx) { log.error('Failed to clean up job ' + job.id, cleanupEx) }
            }
            flash.error = 'Job submission failed: ' + ex.message
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/jobQueue')
    }

    @Secured(['ROLE_ADMIN'])
    def emailServer() {
        // SMTP secrets must come from environment / external secret store, not from a GSP or DB.
        render(view: 'index', model: [tab: 'email', isAdmin: true,
            smtpHost: System.getenv('SERVER_ADMIN_SMTP_HOST') ?: '',
            smtpPort: System.getenv('SERVER_ADMIN_SMTP_PORT') ?: '',
            smtpFrom: System.getenv('SERVER_ADMIN_SMTP_FROM') ?: '',
            smtpConfigured: !!System.getenv('SERVER_ADMIN_SMTP_HOST')])
    }


    @Secured(['ROLE_ADMIN'])
    def workers() {
        render(view:'index', model:[tab:'workers', isAdmin:true,
            workers:RemoteWorker.list(sort:'dateCreated',order:'desc'),
            scripts:WorkerScript.list(sort:'dateCreated',order:'desc')])
    }

    @Secured(['permitAll'])
    def registerWorker() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            if ((request.contentLengthLong ?: 0) > 8192) { response.sendError(413); return }
            def body = request.JSON
            RemoteWorker worker = remoteWorkerService.register(body as Map)
            render(contentType:'application/json', text:groovy.json.JsonOutput.toJson(
                [workerId:worker.workerId, status:worker.status]))
        } catch (Exception ex) { response.sendError(400, 'Registration rejected') }
    }

    @Secured(['permitAll'])
    def workerHeartbeat() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            def body = request.JSON
            RemoteWorker worker = remoteWorkerService.verifyHeartbeat(
                body.workerId as String, body.timestamp as String,
                body.nonce as String, body.signature as String)
            render(contentType:'application/json', text:groovy.json.JsonOutput.toJson(
                [status:'APPROVED', serverTime:System.currentTimeMillis()]))
        } catch (Exception ex) { response.sendError(403, 'Worker authentication failed') }
    }

    @Secured(['ROLE_ADMIN'])
    def setWorkerStatus() {
        if (request.method != 'POST') { response.sendError(405); return }
        try { remoteWorkerService.setStatus(params.long('id'), params.status as String); flash.message = 'Worker status updated.' }
        catch(Exception ex) { flash.error = ex.message }
        response.sendRedirect('/serverAdmin/workers')
    }

    @Secured(['ROLE_ADMIN'])
    def uploadWorkerScript() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            workerScriptService.upload(request.getFile('file'), params.name as String,
                params.version as String, params.jobType as String,
                springSecurityService.currentUser?.username as String)
            flash.message = 'Script uploaded, disabled until explicitly enabled.'
        } catch(Exception ex) { flash.error = ex.message }
        response.sendRedirect('/serverAdmin/workers')
    }

    @Secured(['ROLE_ADMIN'])
    def setWorkerScriptEnabled() {
        if (request.method != 'POST') { response.sendError(405); return }
        try { workerScriptService.setEnabled(params.long('id'), params.enabled == 'true'); flash.message = 'Script updated.' }
        catch(Exception ex) { flash.error = ex.message }
        response.sendRedirect('/serverAdmin/workers')
    }

    private RemoteWorker signedWorker(String operation, String payloadHash) {
        remoteWorkerService.verifyRequest(
            request.getHeader('X-Worker-Id'), request.getHeader('X-Worker-Timestamp'),
            request.getHeader('X-Worker-Nonce'), request.getHeader('X-Worker-Signature'),
            operation, payloadHash)
    }

    private void workerJson(Map value) {
        render(contentType:'application/json', text:groovy.json.JsonOutput.toJson(value))
    }

    @Secured(['permitAll'])
    def workerPoll() {
        if (request.method != 'POST') { response.sendError(405); return }
        try { workerJson(workerDispatchService.claim(signedWorker('poll','-'))) }
        catch (SecurityException ex) { response.sendError(403) }
        catch (Exception ex) { log.error('Worker poll failed',ex); response.sendError(500) }
    }

    @Secured(['permitAll'])
    def workerFetch() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            String kind = params.kind as String
            Long id = params.long('id')
            if (!(kind in ['input','script']) || id == null) { response.sendError(400); return }
            RemoteWorker worker = signedWorker('fetch', "${id}:${kind}")
            ServerJob job = workerDispatchService.owned(worker,id)
            java.nio.file.Path path = kind == 'input' ? workerDispatchService.input(job) : workerDispatchService.script(job)
            response.contentType = kind == 'input' ? 'application/zip' : 'text/x-python'
            response.setHeader('Content-Disposition','attachment; filename="worker-' + kind + '"')
            java.nio.file.Files.copy(path,response.outputStream)
            response.outputStream.flush()
        } catch (SecurityException ex) { response.sendError(403) }
        catch (Exception ex) { log.error('Worker fetch failed',ex); response.sendError(400) }
    }

    @Secured(['permitAll'])
    def workerProgress() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            def body = request.JSON
            String id = body.id?.toString()
            String status = body.status?.toString()
            int percent = (body.percent ?: 0) as Integer
            String message = (body.message ?: '').toString()
            String digest = java.security.MessageDigest.getInstance('SHA-256').digest(
                "${id}:${status}:${percent}:${message}".getBytes('UTF-8')).encodeHex().toString()
            RemoteWorker worker = signedWorker('progress',digest)
            workerDispatchService.progress(worker,Long.parseLong(id),status,percent,message)
            workerJson([ok:true])
        } catch (SecurityException ex) { response.sendError(403) }
        catch (Exception ex) { log.error('Worker progress failed',ex); response.sendError(400) }
    }

    @Secured(['permitAll'])
    def workerResult() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            Long id = params.long('id')
            if (id == null || request.contentLengthLong > WorkerDispatchService.MAX_RESULT) { response.sendError(413); return }
            byte[] bytes = request.inputStream.bytes
            String digest = java.security.MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()
            RemoteWorker worker = signedWorker('result',"${id}:${digest}")
            workerDispatchService.complete(worker,id,bytes)
            workerJson([ok:true])
        } catch (SecurityException ex) { response.sendError(403) }
        catch (Exception ex) { log.error('Worker result failed',ex); response.sendError(400) }
    }

    @Secured(['ROLE_ADMIN'])
    def downloadJobResult() {
        ServerJob job = ServerJob.get(params.long('id'))
        if (!job?.resultFileName) { response.sendError(404); return }
        String root = System.getenv('SERVER_ADMIN_JOB_STORAGE')
        if (!root) { response.sendError(404); return }
        java.nio.file.Path path = java.nio.file.Paths.get(root).toAbsolutePath().normalize().resolve(job.resultFileName).normalize()
        if (!path.startsWith(java.nio.file.Paths.get(root).toAbsolutePath().normalize()) || !java.nio.file.Files.isRegularFile(path)) { response.sendError(404); return }
        response.contentType = 'application/zip'
        response.setHeader('Content-Disposition','attachment; filename="result-' + job.id + '.zip"')
        java.nio.file.Files.copy(path,response.outputStream)
    }

    @Secured(['ROLE_ADMIN'])
    def authorization() { render(view: 'index', model: [tab: 'auth', isAdmin: true]) }

    @Secured(['ROLE_ADMIN'])
    def maintenance() { render(view: 'index', model: [tab: 'maintenance', isAdmin: true]) }

    @Secured(['ROLE_ADMIN'])
    def exportParts() {
        byte[] content = serverAdminMaintenanceService.exportPartsCsv()
        response.contentType = 'text/csv; charset=UTF-8'
        response.setHeader('Content-Disposition', 'attachment; filename="plm-parts-metadata.csv"')
        response.outputStream.write(content)
        response.outputStream.flush()
    }

    @Secured(['ROLE_ADMIN'])
    def backup() {
        byte[] content = serverAdminMaintenanceService.metadataBackupZip()
        response.contentType = 'application/zip'
        response.setHeader('Content-Disposition', 'attachment; filename="plm-metadata-backup.zip"')
        response.outputStream.write(content)
        response.outputStream.flush()
    }

    @Secured(['ROLE_ADMIN'])
    def importParts() {
        if (request.method != 'POST') { response.sendError(405); return }
        MultipartFile csv = request.getFile('file')
        if (!csv || csv.empty) { flash.error = 'Select a CSV file.'; response.sendRedirect(request.contextPath + '/serverAdmin/maintenance'); return }
        if (csv.size > 10L * 1024 * 1024) { response.sendError(413, 'CSV exceeds 10 MB'); return }
        try {
            Map result = serverAdminMaintenanceService.importMetadataCsv(csv.inputStream)
            flash.message = "Updated ${result.updated} existing parts; skipped ${result.skipped} rows."
            if (result.errors) flash.error = result.errors.take(8).join(' | ')
        } catch (Exception ex) {
            log.warn('Bulk metadata import failed', ex)
            flash.error = 'Import failed: ' + ex.message
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/maintenance')
    }
    @Secured(['ROLE_ADMIN'])
    def exportMaterials() {
        byte[] content = serverAdminMaterialsService.exportJson()
        response.contentType = 'application/json; charset=UTF-8'
        response.setHeader('Content-Disposition', 'attachment; filename="materials-library.json"')
        response.outputStream.write(content)
        response.outputStream.flush()
    }

    @Secured(['ROLE_ADMIN'])
    def importMaterials() {
        if (request.method != 'POST') { response.sendError(405); return }
        MultipartFile upload = request.getFile('file')
        if (!upload || upload.empty) {
            flash.error = 'Select a Materials Library JSON file.'
        } else if (upload.size > 20L * 1024 * 1024) {
            response.sendError(413, 'Materials JSON exceeds 20 MB'); return
        } else {
            try {
                Map result = serverAdminMaterialsService.importJson(upload.inputStream)
                flash.message = "Materials imported: ${result.categories} categories, ${result.materials} materials."
            } catch (Exception ex) {
                log.warn('Materials import failed', ex)
                flash.error = 'Materials import failed: ' + ex.message
            }
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/maintenance')
    }

}
