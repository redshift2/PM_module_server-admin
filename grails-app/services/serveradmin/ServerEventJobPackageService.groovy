package serveradmin

import groovy.json.JsonOutput
import taack.ui.TaackUiConfiguration
import java.nio.file.*
import java.util.zip.*

/** Standard input ZIP for all dynamically registered worker jobs. */
class ServerEventJobPackageService {
    def grailsApplication
    ServerJobService serverJobService

    void enqueueEventJob(String jobType, Long eventId, String actor) {
        ServerEvent event = ServerEvent.get(eventId)
        if (!event) throw new IllegalArgumentException('Event not found: ' + eventId)
        if (!WorkerScript.findByJobTypeAndEnabled(jobType, true))
            throw new IllegalArgumentException('No enabled script for: ' + jobType)
        String root = System.getenv('SERVER_ADMIN_JOB_STORAGE')
        if (!root) throw new IllegalStateException('SERVER_ADMIN_JOB_STORAGE not configured')
        Path dir = Paths.get(root).toAbsolutePath().normalize()
        Files.createDirectories(dir)
        Map manifest = [jobType:jobType, eventId:event.id, eventType:event.eventType,
            sourceModule:event.sourceModule, entityType:event.entityType,
            entityId:event.entityId, actor:event.actor, message:event.message]
        Path cadFile = null
        if (event.sourceModule?.equalsIgnoreCase('plm') && event.entityId ==~ /[0-9]+/) {
            def partClass = grailsApplication.classLoader.loadClass('plm.PlmFreeCadPart')
            def part = partClass.get(Long.valueOf(event.entityId))
            if (!part) throw new IllegalArgumentException('PLM part not found')
            manifest.partId = part.id
            manifest.cadVersion = "${part.majorVersion ?: 1}.${part.minorVersion ?: 0}"
            manifest.cadSha1 = part.plmContentShaOne
            if (part.plmFilePath) {
                // plmFilePath is a stored filename, not an absolute filesystem path.
                // Use the same storage layout as PlmFreeCadProtoService.
                String storedName = part.plmFilePath.toString()
                if (!(storedName ==~ /[a-fA-F0-9]{40}\.[a-zA-Z0-9]+/))
                    throw new IllegalStateException('Invalid PLM CAD stored filename: ' + storedName)
                Path modelDir = Paths.get(TaackUiConfiguration.root, 'plmFreecad', 'model')
                        .toAbsolutePath().normalize()
                cadFile = modelDir.resolve(storedName).normalize()
                if (!cadFile.startsWith(modelDir) || !Files.isRegularFile(cadFile))
                    throw new IllegalStateException('PLM CAD file unavailable: ' + cadFile)
                manifest.inputFile = 'part.FCStd'
            }
        }
        ServerJob job = serverJobService.submit(jobType, actor, manifest)
        Path tmp = Files.createTempFile(dir, 'event-job-', '.tmp')
        try {
            ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))
            try {
                zip.putNextEntry(new ZipEntry('manifest.json'))
                zip.write(JsonOutput.toJson(manifest).getBytes('UTF-8'))
                zip.closeEntry()
                if (cadFile != null) {
                    zip.putNextEntry(new ZipEntry('part.FCStd'))
                    Files.copy(cadFile, zip)
                    zip.closeEntry()
                }
            } finally { zip.close() }
            String filename = 'job-' + job.id + '.zip'
            Files.move(tmp, dir.resolve(filename), StandardCopyOption.REPLACE_EXISTING)
            serverJobService.setPackageFileName(job.id, filename)
        } finally { Files.deleteIfExists(tmp) }
    }
}
