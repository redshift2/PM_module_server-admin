package serveradmin

/** Module discovery includes app modules that are not listed by the Grails plugin manager. */
class ServerAdminModuleInfoService {
    def grailsApplication
    def grailsPluginManager

    List<Map> installedModules() {
        Map<String, Map> found = [:]
        def add = { String name, String version, String kind ->
            if (!name) return
            String key = name.toLowerCase(java.util.Locale.ROOT)
            if (!found.containsKey(key) || found[key].version == 'Unknown')
                found[key] = [name:name, version:version ?: 'Unknown', type:kind]
        }
        // Grails plugins (including plugins registered in the host application).
        try {
            grailsPluginManager?.allPlugins?.each { p ->
                add(p.name?.toString(), p.version?.toString(), 'Grails plugin')
            }
        } catch (Exception e) { log.warn('Plugin enumeration failed', e) }

        // App modules are sometimes project dependencies, not visible as Grails plugins.
        // Detect them by their controller classes on the deployed classpath.
        Map<String, List<String>> known = [
            'PM_module_server-admin':['serveradmin.ServerAdminController'],
            'PM_module_materials':['materials.MaterialController'],
            'Parts_Manager_PLM':['plm.PlmController'],
            'PM_module_user_management':['crew.UserController', 'crew.CrewController']
        ]
        ClassLoader loader = this.class.classLoader
        known.each { String name, List<String> classes ->
            boolean present = classes.any { String cn ->
                try { loader.loadClass(cn); true } catch (ClassNotFoundException ignored) { false }
            }
            if (present) add(name, moduleVersion(loader, name), 'Application module')
        }
        // Also report the host application.
        try {
            add(grailsApplication?.metadata?.getApplicationName()?.toString() ?: 'Server',
                grailsApplication?.metadata?.getApplicationVersion()?.toString(), 'Application')
        } catch (Exception ignored) { add('Server', 'Unknown', 'Application') }
        found.values().toList().sort { a, b -> a.name.compareToIgnoreCase(b.name) }
    }

    private static String moduleVersion(ClassLoader loader, String moduleName) {
        // Standard Gradle/Grails build-info.properties, if bundled in the module JAR.
        String resource = 'build-info.properties'
        try {
            Enumeration<java.net.URL> urls = loader.getResources(resource)
            while (urls.hasMoreElements()) {
                Properties props = new Properties()
                urls.nextElement().openStream().withCloseable { props.load(it) }
                String artifact = props.getProperty('info.app.name') ?: props.getProperty('name') ?: ''
                if (artifact.equalsIgnoreCase(moduleName) || artifact.toLowerCase().contains(moduleName.toLowerCase().replace('pm_module_', ''))) {
                    return props.getProperty('info.app.version') ?: props.getProperty('version') ?: 'Unknown'
                }
            }
        } catch (Exception ignored) { }
        'Unknown'
    }
}
