package com.flashsale.notification.adapter.mail;

import com.flashsale.notification.application.NotificationDeliveryRepository;
import com.flashsale.notification.application.NotificationSender;
import com.flashsale.notification.domain.NotificationDelivery;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.internet.MimeMessage;

@Component
public class EmailNotificationSender implements NotificationSender {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final NotificationDeliveryRepository deliveryRepository;

    public EmailNotificationSender(JavaMailSender mailSender, TemplateEngine templateEngine,
                                    NotificationDeliveryRepository deliveryRepository) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.deliveryRepository = deliveryRepository;
    }

    @Override
    @Async
    public void send(NotificationDelivery delivery) {
        NotificationDelivery saved = deliveryRepository.save(delivery);
        try {
            Context context = new Context();
            context.setVariable("email", saved.getRecipient());
            String html = templateEngine.process("email/" + saved.getTemplate(), context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setTo(saved.getRecipient());
            helper.setSubject("Welcome to FlashSale");
            helper.setText(html, true);

            mailSender.send(message);
            saved.markSent();
        } catch (Exception e) {
            saved.markFailed(e.getMessage());
        }
        deliveryRepository.save(saved);
    }
}
