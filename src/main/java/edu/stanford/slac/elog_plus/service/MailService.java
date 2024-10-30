package edu.stanford.slac.elog_plus.service;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.PersonDTO;
import edu.stanford.slac.ad.eed.baselib.service.PeopleGroupService;
import edu.stanford.slac.elog_plus.config.ELOGAppProperties;
import edu.stanford.slac.elog_plus.model.Entry;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Log4j2
@Service
@AllArgsConstructor
public class MailService {
    private ELOGAppProperties appProperties;
    private JavaMailSender mailSender;
    private TemplateEngine templateEngine;
    private PeopleGroupService peopleGroupService;

    /**
     * Send an email notification to the specified users when a new entry is created
     *
     * @param to              the list of email addresses to send the notification to
     * @param newlyCreatedEntry the newly created entry
     */
    public void sentNewEmailNotification(List<PersonDTO> to, Entry newlyCreatedEntry){
        if(to == null) return;
        // fetch the creator of the entry
        var entryCreator = newlyCreatedEntry.getCreatedBy()!=null?peopleGroupService.findPersonByEMail(newlyCreatedEntry.getCreatedBy()):null;

        // Prepare the email message
        to.forEach(
                person -> {
                    try {
                        log.info("Sending new entry notification to '{}' for entry '{}'", person.mail(), newlyCreatedEntry.getTitle());
                        MimeMessage message = mailSender.createMimeMessage();
                        MimeMessageHelper helper = new MimeMessageHelper(message, true);
                        helper.setTo(person.mail());
                        helper.setSubject("SLAC-ELOG: New Entry Notification");
                        // Set the Thymeleaf context with template variables
                        Context context = new Context();
                        context.setVariables(
                                Map.of(
                                        "name", person.commonName(),
                                        "createdBy", (entryCreator!=null?entryCreator.commonName():"Automatic notification"),
                                        "createdAt", newlyCreatedEntry.getCreatedDate(),
                                        "logDetails", newlyCreatedEntry.getTitle(),
                                        "logLink", "%s/%s".formatted(appProperties.getShowEntryExternalLinkPrefix(), newlyCreatedEntry.getId()),
                                        "thisYear", LocalDateTime.now().getYear()

                                )
                        );
                        // Generate the email content from the template
                        String htmlContent = templateEngine.process("newEntryEmailTemplate", context);
                        helper.setText(htmlContent, true);

                        // Send the email
                        mailSender.send(message);
                        log.info("New entry notification sent to: " + to);
                    } catch (MessagingException e) {
                        log.error("Error sending email to: " + person.mail(), e);
                    }
                }
        );

    }
}
