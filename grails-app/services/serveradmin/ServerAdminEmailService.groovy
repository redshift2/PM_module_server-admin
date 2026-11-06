package serveradmin

import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mail.SimpleMailMessage

/** Outbound SMTP; credentials read only from environment. */
class ServerAdminEmailService {
    JavaMailSenderImpl sender() {
        String host = System.getenv('SERVER_ADMIN_SMTP_HOST')
        String from = System.getenv('SERVER_ADMIN_SMTP_FROM')
        if (!host || !from) throw new IllegalStateException('SMTP host/from not configured')
        def mail = new JavaMailSenderImpl(host: host, port: (System.getenv('SERVER_ADMIN_SMTP_PORT') ?: '587').toInteger())
        mail.username = System.getenv('SERVER_ADMIN_SMTP_USERNAME')
        mail.password = System.getenv('SERVER_ADMIN_SMTP_PASSWORD')
        Properties props = mail.javaMailProperties
        props.put('mail.smtp.auth', mail.username ? 'true' : 'false')
        props.put('mail.smtp.starttls.enable', System.getenv('SERVER_ADMIN_SMTP_STARTTLS') ?: 'true')
        props.put('mail.smtp.connectiontimeout', '10000')
        props.put('mail.smtp.timeout', '10000')
        props.put('mail.smtp.writetimeout', '10000')
        mail
    }
    void send(String to, String subject, String body) {
        if (!to || !to.matches('^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$')) throw new IllegalArgumentException('Invalid recipient')
        def msg = new SimpleMailMessage()
        msg.from = System.getenv('SERVER_ADMIN_SMTP_FROM')
        msg.to = to
        msg.subject = subject.take(200)
        msg.text = body.take(10000)
        sender().send(msg)
    }
}