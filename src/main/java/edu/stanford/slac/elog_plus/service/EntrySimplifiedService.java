package edu.stanford.slac.elog_plus.service;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.PersonDTO;
import edu.stanford.slac.ad.eed.baselib.exception.ControllerLogicException;
import edu.stanford.slac.elog_plus.api.v2.dto.NewEntryDTO;
import edu.stanford.slac.elog_plus.api.v2.mapper.EntryMapperV2;
import edu.stanford.slac.elog_plus.model.FileObjectDescription;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Log4j2
@Service
@AllArgsConstructor
public class EntrySimplifiedService {
    final private EntryMapperV2 entryMapperV2;
    final private EntryService entryService;
    final private LogbookService logbookService;
    final private AttachmentService attachmentService;

    /**
     * Create a new entry with attachment
     *
     * this service is a simplified version of the base one, where the user
     * can use the name of the logbooks and tags instead to use id
     *
     * @param createdBy the person who created the entry
     * @param newEntry  the entry to create
     * @param files     the files to attach to the entry
     * @return the id of the created entry
     */
    public String createNew(PersonDTO createdBy, NewEntryDTO newEntry, MultipartFile[] files) {
        // check for the attachment
        log.info("[{}] Create attachment", newEntry.title());
        Set<String> attachments= new HashSet<>();
        if (files != null) {
            attachments = Arrays.stream(files).map(
                    file -> {
                        try {
                            var attachment = FileObjectDescription
                                    .builder()
                                    .fileName(
                                            file.getOriginalFilename()
                                    )
                                    .contentType(
                                            file.getContentType()
                                    )
                                    .is(
                                            file.getInputStream()
                                    )
                                    .build();
                            return attachmentService.createAttachment(
                                    attachment,
                                    true
                            );
                        } catch (IOException e) {
                            throw ControllerLogicException
                                    .builder()
                                    .errorCode(-1)
                                    .errorMessage(e.getMessage())
                                    .errorDomain("EntriesController:newEntryWithAttachment")
                                    .build();
                        }
                    }
            ).collect(Collectors.toSet());
        }
        log.info("[Create entry  {}] logbook conversion", newEntry.title());
        Set<String> logbooks = logbookService.getLogbooksIdByNames(newEntry.logbooks());
        log.info("[Create entry  {}] tags conversion", newEntry.title());
        Set<String> tags = logbookService.getTagsIdsByNameThatBelongToEachLogbook(newEntry.tags(), logbooks);
        log.info("[Create entry  {}] create entry", newEntry.title());
        return entryService.createNew(
                entryMapperV2.toEntryNewDTO(newEntry, logbooks, tags, attachments),
                createdBy
        );
    }
}
