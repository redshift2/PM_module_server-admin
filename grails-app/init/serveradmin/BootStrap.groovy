package serveradmin

/** Run versioned Server Admin migrations at application startup. */
class BootStrap {
    ServerAdminDatabaseMigrationService serverAdminDatabaseMigrationService

    def init = { servletContext ->
        serverAdminDatabaseMigrationService.migrate()
    }

    def destroy = { }
}
