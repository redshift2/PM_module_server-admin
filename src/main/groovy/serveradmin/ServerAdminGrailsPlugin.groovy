package serveradmin
import grails.plugins.Plugin
class ServerAdminGrailsPlugin extends Plugin {
    def grailsVersion = '7.0.0 > *'
    def title = 'Server Administration'
    def author = 'Parts Manager'
    def description = 'Server information, jobs, authorization and maintenance'
    def profiles = ['web']
}
