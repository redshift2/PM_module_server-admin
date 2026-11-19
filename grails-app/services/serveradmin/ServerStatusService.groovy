package serveradmin

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/** Read-only, bounded server monitoring. No Docker socket or shell commands. */
class ServerStatusService {
    def grailsApplication

    Map snapshot() {
        def runtime = Runtime.runtime
        def os = ManagementFactory.operatingSystemMXBean
        def mem = ManagementFactory.memoryMXBean.heapMemoryUsage
        def runtimeBean = ManagementFactory.runtimeMXBean
        ensureLogCapture()
        Map users = [loggedInUsers: null, activeSessions: null, registeredUsers: null]
        try {
            def registry = grailsApplication.mainContext.containsBean('sessionRegistry') ?
                    grailsApplication.mainContext.getBean('sessionRegistry') : null
            if (registry) {
                def principals = registry.allPrincipals ?: []
                def sessions = principals.collectMany { registry.getAllSessions(it, false) ?: [] }
                users.loggedInUsers = principals.count { p -> (registry.getAllSessions(p, false) ?: []).size() > 0 }
                users.activeSessions = sessions.size()
            }
        } catch (Exception ignored) { /* Session tracking is optional. Never misreport zero. */ }
        try { users.registeredUsers = taack.user.TaackUser.count() } catch (Exception ignored) { }
        double processCpu = -1d
        double systemCpu = -1d
        long physicalTotal = -1L
        long physicalFree = -1L
        try {
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                def mx = (com.sun.management.OperatingSystemMXBean) os
                processCpu = mx.processCpuLoad
                systemCpu = mx.cpuLoad
                physicalTotal = mx.totalMemorySize
                physicalFree = mx.freeMemorySize
            }
        } catch (Exception ignored) { }
        String recentLogs = logCapture()?.contents() ?: ''
        return [timestamp: System.currentTimeMillis(),
                cpu: [systemPercent: percent(systemCpu), processPercent: percent(processCpu), cores: os.availableProcessors, loadAverage: os.systemLoadAverage],
                memory: [heapUsed: mem.used, heapCommitted: mem.committed, heapMax: mem.max,
                         physicalTotal: physicalTotal, physicalFree: physicalFree],
                server: [uptimeMillis: runtimeBean.uptime, javaVersion: System.getProperty('java.version'),
                         os: System.getProperty('os.name'), processors: runtime.availableProcessors()],
                storage: [database: storageInfo('/database'), vault: storageInfo('/root/intranetFilesDev')],
                users: users,
                logs: [available: true, text: recentLogs.length() > 150000 ? recentLogs.substring(recentLogs.length() - 150000) : recentLogs,
                       message: 'Recent application logs captured since the Server Status monitor started.']]
    }

    private static Object percent(double value) {
        value < 0 || Double.isNaN(value) ? null : Math.round(value * 1000d) / 10d
    }

    private static final Object captureLock = new Object()
    private static ServerStatusLogAppender capturedAppender
    private static final Map<String, Map> storageCache = [:]

    private static ServerStatusLogAppender logCapture() {
        ensureLogCapture()
        capturedAppender
    }

    private static void ensureLogCapture() {
        if (capturedAppender) return
        synchronized (captureLock) {
            if (capturedAppender) return
            def factory = LoggerFactory.getILoggerFactory()
            if (!(factory instanceof ch.qos.logback.classic.LoggerContext)) return
            def root = factory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            def appender = new ServerStatusLogAppender()
            appender.context = factory
            appender.name = 'PARTS_MANAGER_SERVER_STATUS'
            appender.start()
            root.addAppender(appender)
            capturedAppender = appender
        }
    }

    String downloadLogs() {
        logCapture()?.contents() ?: 'No application logs captured yet.\n'
    }

    private static Map storageInfo(String path) {
        File folder = new File(path)
        if (!folder.exists() || !folder.isDirectory())
            return [path: path, available: false, message: 'Directory not found or not mounted']
        long now = System.currentTimeMillis()
        synchronized (storageCache) {
            Map previous = storageCache[path]
            if (previous && now - (previous.checkedAt as long) < 60000L) return previous
        }
        long size = 0L
        long files = 0L
        boolean complete = true
        try {
            // Do not follow symlinks; cap traversal to keep the monitoring endpoint responsive.
            def queue = new ArrayDeque<File>()
            queue.add(folder)
            long examined = 0L
            while (!queue.isEmpty()) {
                if (++examined > 200000L) { complete = false; break }
                File f = queue.removeFirst()
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                if (f.isDirectory()) {
                    File[] children = f.listFiles()
                    if (children == null) { complete = false; continue }
                    children.each { queue.addLast(it) }
                } else if (f.isFile()) { size += f.length(); files++ }
            }
        } catch (Exception ignored) { complete = false }
        Map result = [path: path, available: true, total: folder.totalSpace,
                      free: folder.freeSpace, usable: folder.usableSpace,
                      dataBytes: size, fileCount: files, complete: complete, checkedAt: now]
        synchronized (storageCache) { storageCache[path] = result }
        result
    }
}
