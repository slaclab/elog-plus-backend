package edu.stanford.slac.elog_plus.repository;

import edu.stanford.slac.elog_plus.exception.LogbookNotFound;
import edu.stanford.slac.elog_plus.model.Logbook;
import edu.stanford.slac.elog_plus.model.Shift;
import edu.stanford.slac.elog_plus.model.Tag;
import lombok.AllArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.util.*;

import static edu.stanford.slac.ad.eed.baselib.exception.Utility.assertion;


@Repository
@AllArgsConstructor
public class LogbookRepositoryImpl implements LogbookRepositoryCustom {
    final private MongoTemplate mongoTemplate;

    @Override
    public String ensureTag(String logbookId, Tag newTag) throws UnsupportedEncodingException, NoSuchAlgorithmException {
        String newID = UUID.randomUUID().toString();
        newTag.setId(newID);
        Query query = new Query(
                Criteria.where("id").is(logbookId)
                        .and("tags.name").ne(newTag.getName())
        );

        Update update = new Update()
                .addToSet("tags", newTag);

        Logbook lb = mongoTemplate.findAndModify(
                query,
                update,
                Logbook.class
        );
        if(lb==null || lb.getTags()==null) {
            Query queryForTagID = new Query(
                    Criteria.where("id").is(logbookId)
            );
            queryForTagID.fields().include("tags");
            lb = mongoTemplate.findOne(
                    queryForTagID,
                    Logbook.class
            );
            if(lb!=null) {
                newID = lb.getTags().stream().filter(
                        t->t.getName().compareToIgnoreCase(newTag.getName())==0
                )
                        .findFirst()
                        .map(Tag::getId)
                        .orElse(null);
            }
        } else {
            newID = lb.getTags().stream().filter(
                    t->t.getName().compareToIgnoreCase(newTag.getName())==0
            )
                    .findFirst()
                    .map(Tag::getId)
                    .orElse(newID);
        }
        return newID;
    }

    @Override
    public List<Tag> getAllTagFor(String logbookId) {
        Query q = new Query();
        q.addCriteria(
                Criteria.where("id").is(logbookId)
        );
        q.fields().include("tags");
        Logbook lb = mongoTemplate.findOne(q, Logbook.class);
        assertion(
                () -> lb != null,
                LogbookNotFound.logbookNotFoundBuilder()
                        .errorCode(-1)
                        .errorDomain("LogbookRepositoryImpl:getAllTagFor")
                        .build()
        );
        return Objects.requireNonNull(lb).getTags();
    }

    @Override
    public boolean tagExistByName(String logbookId, String tagName) {
        Query query = new Query();
        query.addCriteria(
                Criteria
                        .where("id").is(logbookId)
                        .and("tags").elemMatch(
                                Criteria.where("name").is(tagName)
                        )
        );

        Logbook l = mongoTemplate.findOne(query, Logbook.class);
        if (l != null) {
            return l.getTags().size() == 1;
        } else {
            return false;
        }
    }

    @Override
    public List<String> getAllLogbook() {
        List<String> result = new ArrayList<>();
        Query query = new Query();
        query.fields().include("name");
        List<Logbook> l = mongoTemplate.find(query, Logbook.class);
        for (Logbook lb :
                l) {
            result.add(lb.getName());
        }
        return result;
    }

    @Override
    public List<String> getAllLogbookIds() {
        List<String> result = new ArrayList<>();
        Query query = new Query();
        query.fields().include("id");
        List<Logbook> l = mongoTemplate.find(query, Logbook.class);
        for (Logbook lb :
                l) {
            result.add(lb.getId());
        }
        return result;
    }

    @Override
    public List<Shift> getAllShift(String logbookID) {
        Query q = new Query();
        q.addCriteria(
                Criteria.where("id").is(logbookID)
        );
        q.fields().include("shifts");
        Logbook lb = mongoTemplate.findOne(q, Logbook.class);
        assertion(
                () -> lb != null,
                LogbookNotFound.logbookNotFoundBuilder()
                        .errorCode(-1)
                        .errorDomain("LogbookRepositoryImpl:getAllShift")
                        .build()
        );
        return Objects.requireNonNull(lb).getShifts();
    }

    @Override
    public Optional<Shift> findShiftFromLocalTime(String logbookId, LocalTime localTime) {
        Optional<Shift> result = null;
        localTime = localTime.withSecond(0).withNano(0);
        int minutesFromMidnight = localTime.getHour() * 60 + localTime.getMinute();
        Query q = new Query();
        q.addCriteria(
                Criteria.where("id").is(logbookId)
        );
        q.fields()
                .include("shifts");
        Logbook l = mongoTemplate.findOne(q, Logbook.class);
        if (l != null) {
            LocalTime finalLocalTime = localTime;
            result = l.getShifts()
                    .stream()
                    .filter(
                            s ->
                                    (finalLocalTime.equals(s.getFromTime()) || finalLocalTime.equals(s.getToTime())) ||
                                            (
                                                    finalLocalTime.isAfter(s.getFromTime()) && finalLocalTime.isBefore(s.getToTime())
                                            )

                    )
                    .findFirst();
        }
        return result;
    }

    @Override
    public Optional<Shift> findShiftFromLocalTimeWithLogbookName(String logbookName, LocalTime localTime) {
        Optional<Shift> result = Optional.empty();
        Query q = new Query();
        q.addCriteria(
                Criteria.where("name").is(logbookName)
        );
        q.fields()
                .include("shifts");
        Logbook l = mongoTemplate.findOne(q, Logbook.class);
        if (l != null) {
            result = l.getShifts()
                    .stream()
                    .filter(
                            s ->
                                    (localTime.equals(s.getFromTime()) || localTime.equals(s.getToTime())) ||
                                            (
                                                    localTime.isAfter(s.getFromTime()) && localTime.isBefore(s.getToTime())
                                            )

                    )
                    .findFirst();
        }
        return result;
    }

    @Override
    public Optional<Shift> findShiftFromLocalTimeWithLogbookId(String logbookId, LocalTime localTime)  {
        Optional<Shift> result = Optional.empty();
        Query q = new Query();
        q.addCriteria(
                Criteria.where("id").is(logbookId)
        );
        q.fields()
                .include("shifts");
        Logbook l = mongoTemplate.findOne(q, Logbook.class);
        if (l != null) {
            result = l.getShifts()
                    .stream()
                    .filter(
                            s ->
                                    (localTime.equals(s.getFromTime()) || localTime.equals(s.getToTime())) ||
                                            (
                                                    localTime.isAfter(s.getFromTime()) && localTime.isBefore(s.getToTime())
                                            )

                    )
                    .findFirst();
        }
        return result;
    }

    @Override
    @Cacheable(value = "tags", key = "'by_id_'+#tagId")
    public Optional<Tag> getTagsByID(String tagId) {
        Query q = new Query();
        q.addCriteria(
                Criteria.where("tags.id").is(tagId)
        );
        q.fields().include("tags");
        Logbook l = mongoTemplate.findOne(q, Logbook.class);
        if (l != null) {
            return l.getTags()
                    .stream()
                    .filter(
                            t-> t.getId().compareTo(tagId)==0
                    )
                    .findFirst();
        } else {
            return Optional.empty();
        }
    }
}
