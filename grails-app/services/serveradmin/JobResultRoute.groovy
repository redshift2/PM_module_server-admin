package serveradmin

/** Destination is independent of the worker script/job type. */
class JobResultRoute {
    static final Map<String, String> OPTIONS = [
        '3D_VIEWER':'3D Viewer', 'METADATA':'Metadata',
        'ATTACHMENTS':'Attachments', 'THUMBNAILS':'Thumbnails',
        'BOM':'BOM', 'QUALITY':'Quality / Inspection',
        'JOB_RESULTS':'Job Results Only'
    ].asImmutable()

    static String legacyDefault(String jobType) {
        if (jobType in ['CAD_TO_GLB','CAD_TO_X3D']) return '3D_VIEWER'
        if (jobType == 'CAD_PROPERTIES') return 'METADATA'
        return 'JOB_RESULTS'
    }

    static String validate(String route) {
        if (!OPTIONS.containsKey(route)) throw new IllegalArgumentException('Invalid Route Data destination: ' + route)
        return route
    }
}
