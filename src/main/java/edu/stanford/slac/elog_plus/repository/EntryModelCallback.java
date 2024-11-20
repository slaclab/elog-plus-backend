package edu.stanford.slac.elog_plus.repository;

import edu.stanford.slac.elog_plus.model.Entry;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class EntryModelCallback implements BeforeConvertCallback<Entry> {

    @NotNull
    @Override
    public Entry onBeforeConvert(Entry entry, @NotNull String collection) {
        LocalDateTime createdDate = entry.getCreatedDate();
        if (createdDate == null) {
            createdDate = LocalDateTime.now();
            entry.setCreatedDate(createdDate);
        }

        if (entry.getLoggedAt() == null) {
            entry.setLoggedAt(createdDate);
        }
        if (entry.getEventAt() == null) {
            entry.setEventAt(createdDate);
        }
        return entry;
    }
}