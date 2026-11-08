package serveradmin
import grails.plugins.Plugin
class ServerAdminGrailsPlugin extends Plugin {
    def grailsVersion = '7.0.0 > *'
    def title = 'Server Administration'
    def author = 'Parts Manager'
    def description = 'Server information, jobs, authorization and maintenance'
    def profiles = ['web']

    // Enable Spring @Scheduled methods for services in this plugin.
    // Reuse Spring's conventional infrastructure bean name.
    Closure doWithSpring() { { ->
        'org.springframework.context.annotation.internalScheduledAnnotationProcessor'(
            org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
        )
    } }
}
