package serveradmin

import javax.sql.DataSource
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.net.JarURLConnection
import java.net.URL
import java.util.jar.JarFile

/**
 * Lightweight, H2-specific, append-only migration runner.
 * Migrations are classpath resources under src/main/resources/plm/db/migration.
 * Supports unpacked resources and packaged Server Admin JARs.
 *
 * H2 DDL commits implicitly: scripts must be idempotent. Run one server instance
 * during migrations. Never edit a migration that has already been applied.
 */
class ServerAdminDatabaseMigrationService {

    DataSource dataSource

    private static final String MIGRATION_DIR = 'serveradmin/db/migration/'
    private static final String MIGRATION_PATTERN = /^V(\d+)__(.+)\.sql$/

    private List<String> discoverMigrations() {
        Set<String> names = new LinkedHashSet<String>()
        ClassLoader loader = this.class.classLoader
        Enumeration<URL> locations = loader.getResources(MIGRATION_DIR)

        while (locations.hasMoreElements()) {
            URL location = locations.nextElement()
            if (location.protocol == 'file') {
                File directory = new File(location.toURI())
                directory.listFiles()?.each { File f ->
                    if (f.isFile() && f.name ==~ MIGRATION_PATTERN) names.add(f.name)
                }
            } else if (location.protocol == 'jar') {
                JarURLConnection jarConnection = (JarURLConnection) location.openConnection()
                JarFile jar = jarConnection.jarFile
                try {
                    Enumeration entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        def entry = entries.nextElement()
                        String entryName = entry.name
                        if (!entry.directory && entryName.startsWith(MIGRATION_DIR)) {
                            String filename = entryName.substring(MIGRATION_DIR.length())
                            if (!filename.contains('/') && filename ==~ MIGRATION_PATTERN) names.add(filename)
                        }
                    }
                } finally {
                    jar.close()
                }
            } else {
                log.warn("Unsupported Server Admin migration resource URL: ${location}")
            }
        }

        // Some JARs omit explicit directory entries, making getResources(dir)
        // return nothing. Inspect the JAR containing this service as a fallback.
        if (names.isEmpty()) {
            URL serviceClass = loader.getResource('plm/ServerAdminDatabaseMigrationService.class')
            if (serviceClass?.protocol == 'jar') {
                JarURLConnection jarConnection = (JarURLConnection) serviceClass.openConnection()
                JarFile jar = jarConnection.jarFile
                try {
                    Enumeration entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        def entry = entries.nextElement()
                        String entryName = entry.name
                        if (!entry.directory && entryName.startsWith(MIGRATION_DIR)) {
                            String filename = entryName.substring(MIGRATION_DIR.length())
                            if (!filename.contains('/') && filename ==~ MIGRATION_PATTERN) names.add(filename)
                        }
                    }
                } finally {
                    jar.close()
                }
            }
        }

        if (names.isEmpty()) {
            throw new IllegalStateException("No Server Admin migrations found in classpath directory ${MIGRATION_DIR}")
        }

        List<String> sorted = new ArrayList<String>(names)
        sorted.sort { String a, String b ->
            Integer va = Integer.parseInt((a =~ MIGRATION_PATTERN)[0][1] as String)
            Integer vb = Integer.parseInt((b =~ MIGRATION_PATTERN)[0][1] as String)
            va <=> vb ?: a <=> b
        }
        Set<Integer> versions = new HashSet<Integer>()
        sorted.each { String filename ->
            int version = Integer.parseInt((filename =~ MIGRATION_PATTERN)[0][1] as String)
            if (!versions.add(version)) {
                throw new IllegalStateException("Duplicate Server Admin migration version ${version}: ${sorted}")
            }
        }
        return sorted
    }

    void migrate() {
        Connection connection = dataSource.getConnection()
        try {
            String database = connection.metaData.databaseProductName
            if (!'H2'.equalsIgnoreCase(database)) {
                throw new IllegalStateException("Server Admin migrations currently support H2 only; detected: ${database}")
            }
            List<String> migrations = discoverMigrations()
            log.info("Server Admin migrations discovered: ${migrations.join(', ')}")
            ensureHistoryTable(connection)
            for (String filename : migrations) {
                applyMigration(connection, filename)
            }
            log.info('Server Admin database migrations: completed successfully.')
        } finally {
            connection.close()
        }
    }

    private void ensureHistoryTable(Connection connection) {
        Statement statement = connection.createStatement()
        try {
            statement.execute('''
                CREATE TABLE IF NOT EXISTS SERVERADMIN_SCHEMA_MIGRATION (
                    VERSION INTEGER PRIMARY KEY,
                    DESCRIPTION VARCHAR(255) NOT NULL,
                    SCRIPT VARCHAR(255) NOT NULL,
                    CHECKSUM VARCHAR(64) NOT NULL,
                    INSTALLED_ON TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
                )
            ''')
        } finally {
            statement.close()
        }
    }

    private void applyMigration(Connection connection, String filename) {
        def matcher = (filename =~ MIGRATION_PATTERN)
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid migration filename: ${filename}")
        }
        int version = Integer.parseInt(matcher.group(1))
        String description = matcher.group(2).replace('_', ' ')
        String resourcePath = MIGRATION_DIR + filename
        InputStream input = this.class.classLoader.getResourceAsStream(resourcePath)
        if (input == null) {
            throw new IllegalStateException("Missing Server Admin migration resource: ${resourcePath}")
        }
        String script
        try {
            script = new String(input.bytes, StandardCharsets.UTF_8)
        } finally {
            input.close()
        }
        String checksum = MessageDigest.getInstance('SHA-256')
            .digest(script.getBytes(StandardCharsets.UTF_8))
            .encodeHex().toString()

        PreparedStatement select = connection.prepareStatement(
            'SELECT CHECKSUM, SCRIPT FROM SERVERADMIN_SCHEMA_MIGRATION WHERE VERSION = ?')
        try {
            select.setInt(1, version)
            ResultSet rows = select.executeQuery()
            try {
                if (rows.next()) {
                    String previousChecksum = rows.getString('CHECKSUM')
                    if (previousChecksum != checksum) {
                        throw new IllegalStateException(
                            "Server Admin migration V${version} was modified after being applied. " +
                            "Previously: ${rows.getString('SCRIPT')}. Create a new migration instead."
                        )
                    }
                    log.info("Server Admin database migration ${filename}: already applied.")
                    return
                }
            } finally {
                rows.close()
            }
        } finally {
            select.close()
        }

        log.info("Server Admin database migration ${filename}: applying...")
        List<String> statements = script.readLines()
            .findAll { String line -> !line.trim().startsWith('--') }
            .join('\n')
            .split(';')
            .collect { String statement -> statement.trim() }
            .findAll { String statement -> !statement.isEmpty() }

        for (String sql : statements) {
            Statement statement = connection.createStatement()
            try {
                statement.execute(sql)
            } finally {
                statement.close()
            }
        }
        PreparedStatement insert = connection.prepareStatement(
            'INSERT INTO SERVERADMIN_SCHEMA_MIGRATION (VERSION, DESCRIPTION, SCRIPT, CHECKSUM) VALUES (?, ?, ?, ?)')
        try {
            insert.setInt(1, version)
            insert.setString(2, description)
            insert.setString(3, filename)
            insert.setString(4, checksum)
            insert.executeUpdate()
        } finally {
            insert.close()
        }
        log.info("Server Admin database migration ${filename}: applied.")
    }
}