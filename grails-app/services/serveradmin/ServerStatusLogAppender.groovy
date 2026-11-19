package serveradmin

import ch.qos.logback.core.AppenderBase
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.classic.spi.StackTraceElementProxy
import java.text.SimpleDateFormat

/** Bounded in-memory capture of application log events; no filesystem or Docker privileges needed. */
class ServerStatusLogAppender extends AppenderBase<ILoggingEvent> {
    private static final int MAX_CHARS = 2 * 1024 * 1024
    private final StringBuilder buffer = new StringBuilder()

    @Override
    protected synchronized void append(ILoggingEvent event) {
        String timestamp = new SimpleDateFormat('yyyy-MM-dd HH:mm:ss.SSS').format(new Date(event.timeStamp))
        buffer.append(timestamp).append(' ').append(event.level).append(' ')
              .append(event.loggerName).append(' : ').append(event.formattedMessage).append('\n')
        IThrowableProxy error = event.throwableProxy
        while (error != null) {
            buffer.append(error.className).append(': ').append(error.message ?: '').append('\n')
            for (StackTraceElementProxy element : error.stackTraceElementProxyArray ?: []) {
                buffer.append('    at ').append(element.toString()).append('\n')
            }
            error = error.cause
        }
        if (buffer.length() > MAX_CHARS) buffer.delete(0, buffer.length() - MAX_CHARS)
    }

    synchronized String contents() { buffer.toString() }
}
