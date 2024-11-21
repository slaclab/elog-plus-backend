package edu.stanford.slac.elog_plus.api.v1.mapper;

import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.exception.TagNotFound;
import edu.stanford.slac.elog_plus.model.Entry;
import edu.stanford.slac.elog_plus.repository.EntryRepository;
import edu.stanford.slac.elog_plus.service.AttachmentService;
import edu.stanford.slac.elog_plus.service.LogbookService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static edu.stanford.slac.ad.eed.baselib.exception.Utility.wrapCatch;

@Mapper(
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        componentModel = "spring"
)
public abstract class EntryMapper {
    public static final String ELOG_ENTRY_REF = "elog-entry-ref";
    public static final String ELOG_ENTRY_REF_ID = "id";
    @Autowired
    private EntryRepository entryRepository;
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private LogbookService logbookService;

    @Mapping(target = "loggedBy", expression = "java(getLoggedBy(entry))")
    @Mapping(target = "followUps", ignore = true)
    @Mapping(source = "logbooks", target = "logbooks", qualifiedByName = "mapToLogbookSummary")
    @Mapping(target = "referencedBy", ignore = true)
    @Mapping(target = "references", ignore = true)
    @Mapping(target = "referencesInBody", expression = "java(checkReferenceInBody(entry.getText()))")
    @Mapping(target = "supersededBy", ignore = true)
    public abstract EntryDTO fromModel(Entry entry);

    @Mapping(target = "loggedBy", expression = "java(getLoggedBy(entry))")
    @Mapping(target = "attachments", ignore = true)
    @Mapping(target = "followUps", ignore = true)
    @Mapping(source = "logbooks", target = "logbooks", qualifiedByName = "mapToLogbookSummary")
    @Mapping(target = "referencedBy", ignore = true)
    @Mapping(target = "references", ignore = true)
    @Mapping(target = "referencesInBody", expression = "java(checkReferenceInBody(entry.getText()))")
    @Mapping(target = "supersededBy", ignore = true)
    public abstract EntryDTO fromModelNoAttachment(Entry entry);

    @Mapping(target = "loggedBy", expression = "java(getLoggedBy(entry))")
    @Mapping(source = "logbooks", target = "logbooks", qualifiedByName = "mapToLogbookSummary")
    @Mapping(target = "followingUp", expression = "java(getFollowingUp(entry.getId()))")
    @Mapping(target = "referencedBy", expression = "java(getReferenceBy(entry.getId()))")
    @Mapping(target = "isEmpty", expression = "java(entry.getText()==null || entry.getText().isEmpty())")
    public abstract EntrySummaryDTO toSearchResult(Entry entry);

    @Mapping(target = "references", expression = "java(createReferences(entryNewDTO.text()))")
    public abstract Entry fromDTO(EntryNewDTO entryNewDTO, String firstName, String lastName, String userName);

    @Mapping(target = "references", expression = "java(createReferences(entryNewDTO.text()))")
    public abstract Entry fromDTO(EntryImportDTO entryNewDTO, List<String> attachments);

    @Named("getFollowingUp")
    public String getFollowingUp(String id) {
        if (id == null || id.isEmpty()) return null;
        return wrapCatch(
                () -> entryRepository.findByFollowUpsContainsAndSupersededByIsNull(id)
                        .map(
                                Entry::getId
                        ).orElse(null),
                -1,
                "EntryMapper::getFollowingUp"
        );
    }

    /**
     * Get the name of the user who logged the entry
     *
     * @param entry the entry to check
     * @return the name of the user who logged the entry
     */
    public String getLoggedBy(Entry entry) {
        // check if the entry is null
        if (entry == null) return null;
        // in case or last name or first name are null
        String loggedBy = "%s %s".formatted(Objects.requireNonNullElse(entry.getFirstName(),""), Objects.requireNonNullElse(entry.getLastName(), ""));
        return loggedBy.trim();
    }

    /**
     * Check if the body text contains one or more reference
     * @param bodyText the text to check
     * @return true if the text contains a reference
     */
    public boolean checkReferenceInBody(String bodyText) {
        if (bodyText == null || bodyText.isEmpty()) return false;
        Document document = Jsoup.parseBodyFragment(bodyText);
        Elements elements = document.select(ELOG_ENTRY_REF);
        return !elements.isEmpty();
    }

    /**
     * Fill the referenced by of an entry
     *
     * @param id the unique id of an entry
     * @return all the id of the entries that refer the entry identified by the Id
     */
    public List<String> getReferenceBy(String id) {
        if (id == null || id.isEmpty()) return null;
        return wrapCatch(
                () -> entryRepository.findAllByReferencesContainsAndSupersededByExists(id, false)
                        .stream()
                        .map(
                                Entry::getId
                        ).toList(),
                -1,
                "EntryMapper::getFollowgetReferenceByingUp"
        );
    }

    /**
     * Create a list of references from the text
     *
     * @param text the text to parse
     * @return a list of references
     */
    @Named("createReferences")
    public List<String> createReferences(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) return result;
        Document document = Jsoup.parseBodyFragment(text);
        Elements elements = document.select(ELOG_ENTRY_REF);
        for (Element element : elements) {
            // Get the 'id' attribute
            if(!element.hasAttr(ELOG_ENTRY_REF_ID)) continue;
            String id = element.attr(ELOG_ENTRY_REF_ID);
            if(id.isEmpty() || result.contains(id)) continue;
            result.add(id);
        }
        return result;
    }

    /**
     * Map a list of attachment id to a list of attachment
     *
     * @param attachments the list of attachment id
     * @return the list of attachment
     */
    public List<AttachmentDTO> map(List<String> attachments) {
        if (attachments == null) {
            return null;
        }

        List<AttachmentDTO> list = new ArrayList<>(attachments.size());
        for (String attachmentID : attachments) {
            list.add(attachmentService.getAttachment(attachmentID));

        }
        return list;
    }

    /**
     * Map a list of logbook id to a list of logbook summary
     *
     * @param logbookIds the list of logbook id
     * @return the list of logbook summary
     */
    @Named("mapToLogbookSummary")
    public List<LogbookSummaryDTO> mapToLogbookSummary(List<String> logbookIds) {
        if (logbookIds == null) {
            return null;
        }

        List<LogbookSummaryDTO> list = new ArrayList<>(logbookIds.size());
        for (String logbookId : logbookIds) {
            list.add(
                    logbookService.getSummaryById(logbookId)
            );

        }
        return list;
    }

    /**
     * Map a list of tag id to a list of tag
     *
     * @param tags the list of tag id
     * @return the list of tag
     */
    public List<TagDTO> fromTagId(List<String> tags) {
        if (tags == null) {
            return null;
        }

        List<TagDTO> list = new ArrayList<>(tags.size());
        for (String tagsId : tags) {
            list.add(
                    logbookService.getTagById(tagsId)
                            .orElseThrow(
                                    () -> TagNotFound.tagNotFoundBuilder()
                                            .errorCode(-1)
                                            .tagName("tagsId")
                                            .errorDomain("EntryMapper::fromTagId")
                                            .build()
                            )
            );
        }
        return list;
    }


}
