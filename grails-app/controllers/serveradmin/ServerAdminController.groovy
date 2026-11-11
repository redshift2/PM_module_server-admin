package serveradmin

import groovy.json.JsonSlurper
import grails.plugin.springsecurity.annotation.Secured
import org.springframework.web.multipart.MultipartFile
import org.springframework.security.core.context.SecurityContextHolder
import grails.gorm.transactions.Transactional

@Secured(['ROLE_PLM_USER', 'ROLE_ADMIN'])
class ServerAdminController {
    def taackAttachmentService
    def grailsPluginManager
    def springSecurityService
    MissingGlbMaintenanceService missingGlbMaintenanceService
    ServerAdminMaintenanceService serverAdminMaintenanceService
    ServerAdminModuleInfoService serverAdminModuleInfoService
    ServerAdminMaterialsService serverAdminMaterialsService
    RemoteWorkerDeletionService remoteWorkerDeletionService
    ServerEventRuleManagementService serverEventRuleManagementService
    ServerEventService serverEventService
    ServerJobService serverJobService
    ServerJobPackageService serverJobPackageService
    ServerEventActionService serverEventActionService
    ServerAdminEmailService serverAdminEmailService
    RemoteWorkerService remoteWorkerService
    WorkerScriptService workerScriptService
    WorkerScriptCatalogService workerScriptCatalogService
    WorkerDispatchService workerDispatchService
    ServerEventJobDispatchService serverEventJobDispatchService

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
        def jobs = serverJobService.recent()
        Set<String> workerIds = jobs.collect { it.assignedWorkerId as String }
                .findAll { it } as Set<String>
        Map<String, String> workerNames = [:]
        if (workerIds) {
            RemoteWorker.findAllByWorkerIdInList(workerIds.toList()).each { RemoteWorker worker ->
                workerNames[worker.workerId] = worker.workerName
            }
        }
        // Resolve PLM part IDs from the job manifest without modifying job history.
        Map<Long, Long> jobPartIds = [:]
        jobs.each { ServerJob job ->
            if (!job.parametersJson) return
            try {
                def parameters = new JsonSlurper().parseText(job.parametersJson)
                def rawId = parameters instanceof Map ? parameters.partId : null
                if (rawId != null && rawId.toString() ==~ /[0-9]+/) {
                    jobPartIds[job.id] = Long.valueOf(rawId.toString())
                }
            } catch (Exception ignored) {
                // Manually submitted jobs may have no PLM manifest.
            }
        }
        render(view: 'index', model: [tab: 'jobs', isAdmin: admin(), jobs: jobs,
                                      workerNames: workerNames, jobPartIds: jobPartIds, routeOptions: JobResultRoute.OPTIONS])
    }

    @Secured(['ROLE_ADMIN'])
    def deleteCompletedJobs() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            int count = serverJobService.deleteCompletedJobs()
            flash.message = count + ' completed job(s) deleted.'
        } catch (Exception ex) {
            log.error('Bulk completed-job deletion failed', ex)
            flash.error = 'Unable to delete completed jobs: ' + ex.message
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/jobQueue')
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
                springSecurityService.currentUser?.username as String, [:], params.routeData as String)
            flash.message = 'Job submitted to queue.'
        } catch (Exception ex) {
            flash.error = ex.message
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/jobQueue')
    }

    @Secured(['ROLE_ADMIN'])
    def events() {
        render(view: 'index', model: [tab: 'events', isAdmin: true, events: serverEventService.recent(), rules: ServerEventRule.list(sort:'id', order:'desc'), activeJobTypes: WorkerScript.findAllByEnabled(true).collect { it.jobType }.unique().sort(), routeOptions: JobResultRoute.OPTIONS, actions: serverEventActionService.recent()])
    }

    @Secured(['ROLE_ADMIN'])
    def createEventRule() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            serverEventRuleManagementService.createRule(params.name as String,
                params.eventType as String, params.actionType as String,
                params.destination as String, params.jobDestination as String, params.routeData as String)
            flash.message = 'Event rule saved.'
        } catch (Exception ex) { log.warn('Create event rule failed', ex); flash.error = ex.message }
        response.sendRedirect(request.contextPath + '/serverAdmin/events')
    }

    @Secured(['ROLE_ADMIN'])
    def updateEventRule() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            serverEventRuleManagementService.updateRule(params.long('id'),
                params.name as String, params.eventType as String,
                params.actionType as String, params.destination as String,
                params.jobDestination as String, params.enabled == 'true', params.routeData as String)
            flash.message = 'Event rule updated.'
        } catch (Exception ex) { log.warn('Update event rule failed', ex); flash.error = ex.message }
        response.sendRedirect(request.contextPath + '/serverAdmin/events')
    }

    @Secured(['ROLE_ADMIN'])
    def deleteEventRule() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            serverEventRuleManagementService.deleteRule(params.long('id'))
            flash.message = 'Event rule deleted.'
        } catch (Exception ex) { log.warn('Delete event rule failed', ex); flash.error = ex.message }
        response.sendRedirect(request.contextPath + '/serverAdmin/events')
    }

    @Secured(['ROLE_ADMIN'])
    def deleteWorker() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            remoteWorkerDeletionService.deleteWorker(params.long('id'))
            flash.message = 'Worker deleted. Its credentials may register again.'
        } catch (Exception ex) { log.warn('Delete worker failed', ex); flash.error = ex.message }
        response.sendRedirect(request.contextPath + '/serverAdmin/workers')
    }

    @Secured(['ROLE_ADMIN'])
    def deleteWorkerScript() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            workerScriptService.deleteScript(params.long('id'), params.boolean('forceDelete'))
            flash.message = 'Script deleted.'
        } catch (Exception ex) { log.warn('Delete script failed', ex); flash.error = ex.message }
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
            job = serverJobService.submit('REMOTE_PACKAGE', springSecurityService.currentUser?.username as String, [:], params.routeData as String)
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
    def refreshWorkerScriptCatalog() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            List<Map> scripts = workerScriptCatalogService.refresh()
            session.workerScriptCatalog = scripts
            flash.message = 'GitHub catalog refreshed: ' + scripts.size() + ' script(s).'
        } catch (Exception ex) {
            log.warn('Worker script catalog refresh failed', ex)
            flash.error = 'Unable to refresh script catalog: ' + ex.message
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/workers')
    }

    @Secured(['ROLE_ADMIN'])
    def installWorkerCatalogScript() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            int index = params.int('index') == null ? -1 : params.int('index')
            List<Map> scripts = session.workerScriptCatalog instanceof List ? (List<Map>) session.workerScriptCatalog : []
            if (index < 0 || index >= scripts.size()) throw new IllegalArgumentException('Refresh the catalog first')
            // Revalidate against the current remote manifest to prevent trusting client input.
            Map candidate = scripts[index]
            if (!workerScriptCatalogService.refresh().contains(candidate)) throw new IllegalArgumentException('Catalog has changed; refresh it')
            workerScriptCatalogService.install(candidate, springSecurityService.currentUser?.username as String)
            flash.message = 'Script installed (disabled). Enable it after review.'
        } catch (Exception ex) {
            log.warn('Worker catalog installation failed', ex)
            flash.error = 'Script installation failed: ' + ex.message
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/workers')
    }

    @Secured(['ROLE_ADMIN'])
    def workers() {
        render(view:'index', model:[tab:'workers', isAdmin:true,
            workers:RemoteWorker.list(sort:'dateCreated',order:'desc'),
            scripts:WorkerScript.list(sort:'dateCreated',order:'desc'),
            catalogScripts:(session.workerScriptCatalog instanceof List ? session.workerScriptCatalog : []),
            activeJobsByWorker: ServerJob.findAllByStatusInList(['ASSIGNED','RUNNING','UPLOADING_RESULTS'])
                .findAll { it.assignedWorkerId }
                .groupBy { it.assignedWorkerId }])
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
        try {
            RemoteWorker worker = signedWorker('poll','-')
            if (worker.status != 'APPROVED') throw new SecurityException('Not approved')
            try {
                serverEventJobDispatchService.processPending(3)
            } catch (Exception dispatchError) {
                log.error("Event dispatch failed; continuing worker poll", dispatchError)
            }
            workerJson(workerDispatchService.claim(worker))
        }
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
    def maintenance() { render(view: 'index', model: [tab: 'maintenance', isAdmin: true,
        glbScripts: WorkerScript.findAllByJobTypeAndEnabled('CAD_TO_GLB', true, [sort:'name'])]) }

    @Secured(['ROLE_ADMIN'])
    def enqueueMissingGlb() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            Long scriptId = params.long('scriptId')
            if (scriptId == null) throw new IllegalArgumentException('Select a GLB script')
            Map summary = missingGlbMaintenanceService.enqueue(scriptId,
                springSecurityService.currentUser?.username as String, params.boolean('forceRecreate'))
            flash.message = "GLB maintenance: scanned ${summary.scanned}, existing ${summary.existing}, queued ${summary.queued}, skipped ${summary.skipped}, errors ${summary.errors}."
        } catch (Exception ex) {
            log.error('Missing GLB maintenance failed', ex)
            flash.error = 'Unable to enqueue missing GLB previews: ' + ex.message
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/maintenance')
    }

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


    // PM_ATTACHMENT_PAGES_V2 - Native Server Admin attachment administration.
    // Do not replace AttachmentController: PLM and other modules use its APIs.
    def attachmentFiles() {
        int page = Math.max(0, Math.min(100000, params.int('page') ?: 0))
        int size = 30
        def files = attachment.Attachment.createCriteria().list(max:size, offset:page*size) {
            eq('active', true)
            order('id','desc')
        }
        render(view:'index', model:[tab:'attachmentFiles',isAdmin:admin(),attachmentFiles:files,attachmentPage:page])
    }

    def attachmentTagged() {
        def groups = attachment.config.TermGroupConfig.values().findAll { it.active }
        String requested = params.group?.toString()
        def group = groups.find { it.toString() == requested }
        def terms = group ? attachment.Term.findAllByActiveAndTermGroupConfig(true, group, [sort:'name',order:'asc']) : []
        Long termId = params.long('termId')
        def selectedTerm = termId ? attachment.Term.get(termId) : null
        if (selectedTerm && (!selectedTerm.active || selectedTerm.termGroupConfig != group)) selectedTerm = null
        def taggedFiles = selectedTerm ? attachment.Attachment.executeQuery(
            'select distinct a from Attachment a join a.documentCategory.tags t where a.active = true and t.id = :termId order by a.id desc',
            [termId:selectedTerm.id], [max:100]) : []
        render(view:'index', model:[tab:'attachmentTagged',isAdmin:admin(),attachmentGroups:groups,
            attachmentGroup:group,attachmentTerms:terms,attachmentSelectedTerm:selectedTerm,attachmentTaggedFiles:taggedFiles])
    }

    // PM_TERMS_TREE_V3: Tree navigation, parent selection, edit and CSV import.
    def attachmentTerms() {
        def groups = attachment.config.TermGroupConfig.values().findAll { it.active }
        def group = groups.find { it.toString() == params.group?.toString() }
        def terms = group ? attachment.Term.findAllByActiveAndTermGroupConfig(true, group, [sort:'name',order:'asc']) : attachment.Term.findAllByActive(true, [sort:'name',order:'asc'])
        def byParent = terms.groupBy { it.parent?.id }
        def rows = []
        def visited = [] as Set
        Closure walk
        walk = { term, int depth ->
            if (visited.contains(term.id)) return
            visited.add(term.id)
            rows << [term:term,depth:depth]
            (byParent[term.id] ?: []).each { child -> walk(child,depth+1) }
        }
        terms.findAll { !it.parent || !terms.any { other -> other.id == it.parent?.id } }.each { walk(it,0) }
        terms.each { walk(it,0) } // Orphan/cyclic records remain visible.
        def selected = params.long('editId') ? terms.find { it.id == params.long('editId') } : null
        render(view:'index', model:[tab:'attachmentTerms',isAdmin:admin(),attachmentGroups:groups,
            attachmentGroup:group,attachmentTerms:terms,attachmentTermRows:rows,attachmentEditTerm:selected])
    }

    @Secured(['ROLE_ADMIN'])
    @Transactional
    def attachmentSaveTerm() {
        if (request.method != 'POST') { response.sendError(405); return }
        def group = attachment.config.TermGroupConfig.values().find { it.active && it.toString() == params.group?.toString() }
        String name = params.name?.toString()?.trim()
        def term = params.long('id') ? attachment.Term.get(params.long('id')) : new attachment.Term(active:true)
        def parent = params.long('parentId') ? attachment.Term.get(params.long('parentId')) : null
        if (!group || !term || (term.id && term.termGroupConfig != group) || !name || name.any { Character.isWhitespace(it as char) } ||
            (parent && (!parent.active || parent.termGroupConfig != group))) {
            flash.error = 'Invalid group, name or parent.'
        } else {
            term.name = name
            term.termGroupConfig = group
            term.parent = parent
            def translations = term.translations instanceof Map ? new LinkedHashMap(term.translations) : [:]
            translations['en'] = name
            term.translations = translations
            if (term.save(flush:true)) flash.message = 'Tag saved.'
            else flash.error = term.errors?.toString() ?: 'Tag could not be saved.'
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/attachmentTerms' +
            (params.returnAll == 'true' ? '' : '?group=' + java.net.URLEncoder.encode(params.group?.toString() ?: '', 'UTF-8')))
    }

    @Secured(['ROLE_ADMIN'])
    @Transactional
    def attachmentImportTermsCsv() {
        if (request.method != 'POST') { response.sendError(405); return }
        def group = attachment.config.TermGroupConfig.values().find { it.active && it.toString() == params.group?.toString() }
        def upload = request.getFile('file')
        if (!group || !upload || upload.empty) {
            flash.error = 'Choose a tag group and a CSV file.'
        } else {
            try {
                def records = []
                upload.inputStream.withReader('UTF-8') { reader ->
                    def csv = new org.apache.commons.csv.CSVParser(reader,
                        org.apache.commons.csv.CSVFormat.DEFAULT.withFirstRecordAsHeader().withIgnoreSurroundingSpaces())
                    def headers = csv.headerMap.keySet().collect { it.toLowerCase().trim() }
                    if (!headers.contains('name') || !headers.contains('parent'))
                        throw new IllegalArgumentException('CSV requires name,parent columns.')
                    csv.each { row ->
                        def values = [:]
                        row.toMap().each { k,val -> values[k.toLowerCase().trim()] = val?.trim() }
                        if (values.name) records << values
                    }
                    csv.close()
                }
                if (records.size() > 2000) throw new IllegalArgumentException('Maximum 2000 tags per import.')
                if (records.collect { it.name }.toSet().size() != records.size())
                    throw new IllegalArgumentException('Duplicate tag names in CSV.')
                def names = [:]
                attachment.Term.findAllByActiveAndTermGroupConfig(true,group).each { names[it.name] = it }
                int created = 0, updated = 0
                // Pass 1: create terms without parents so CSV order is irrelevant.
                records.each { row ->
                    def name = row.name
                    if (name.any { Character.isWhitespace(it as char) }) throw new IllegalArgumentException('Tag names cannot contain spaces: '+name)
                    def term = names[name]
                    if (!term) {
                        term = new attachment.Term(name:name, termGroupConfig:group,active:true,translations:[en:name])
                        if (!term.save(flush:true)) throw new IllegalArgumentException('Could not create '+name+': '+term.errors)
                        names[name] = term
                        created++
                    } else updated++
                }
                // Pass 2: link parents, including parents declared later in the CSV.
                records.each { row ->
                    def term = names[row.name]
                    def parent = row.parent ? names[row.parent] : null
                    if (row.parent && !parent) throw new IllegalArgumentException('Unknown parent for '+row.name+': '+row.parent)
                    term.parent = parent
                    if (!term.save(flush:true)) throw new IllegalArgumentException('Invalid parent for '+row.name+': '+term.errors)
                }
                flash.message = "CSV imported: ${created} created, ${updated} updated."
            } catch(Exception ex) {
                log.warn('Term CSV import failed',ex)
                flash.error = 'CSV import failed: '+ex.message
            }
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/attachmentTerms?group=' +
            java.net.URLEncoder.encode(params.group?.toString() ?: '', 'UTF-8'))
    }

    @Secured(['ROLE_ADMIN'])
    def attachmentCreateTerm() {
        if (request.method != 'POST') { response.sendError(405); return }
        String name = params.name?.toString()?.trim()
        def group = attachment.config.TermGroupConfig.values().find { it.active && it.toString() == params.group?.toString() }
        if (!group || !name || !(name ==~ /[A-Za-z0-9_\-]+/)) {
            flash.error = 'Select a group and enter a term name without spaces.'
        } else {
            def term = new attachment.Term(name:name, termGroupConfig:group, active:true, translations:[en:name])
            if (term.save(flush:true)) flash.message = 'Term created.'
            else flash.error = term.errors?.toString() ?: 'Unable to create term.'
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/attachmentTerms?group=' +
            java.net.URLEncoder.encode(params.group?.toString() ?: '', 'UTF-8'))
    }

    @Secured(['ROLE_ADMIN'])
    def attachmentUpload() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            def f = request.getFile('file')
            if (!f || f.empty) { flash.error = 'Select a file.' }
            else { taackAttachmentService.createAttachment(f); flash.message = 'Attachment uploaded.' }
        } catch(Exception ex) {
            log.error('Server Admin attachment upload failed',ex)
            flash.error = 'Upload failed: ' + ex.message
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/attachmentFiles')
    }

}
