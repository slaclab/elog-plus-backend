package edu.stanford.slac.elog_plus.config;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Log4j2
@Getter
@Setter
@ConfigurationProperties(prefix = "edu.stanford.slac.elog-plus")
public class ELOGAppProperties {
    private String imagePreviewTopic;
    /**
     * The topic where the import entry will be published
     */
    private String importEntryTopic;
    /**
     * The URI prefix for the IPP
     */
    private StorageProperties storage;
    /**
     * The URI prefix for the IPP
     */
    private String ippUriPrefix;
    /**
     * The cron expression for the task that will clean the expired attachments
     */
    private String attachmentCleanExpiredCron;
    /**
     * The expiration time in hours for the attachments
     */
    private Integer attachmentExpirationMinutes;
    /**
     * The URI prefix for the show entry on the UI
     */
    private String showEntryExternalLinkPrefix;
}
