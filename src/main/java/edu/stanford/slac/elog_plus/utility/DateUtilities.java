package edu.stanford.slac.elog_plus.utility;

import edu.stanford.slac.elog_plus.api.v1.dto.ShiftDTO;
import edu.stanford.slac.elog_plus.model.Shift;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DateUtilities {
    public static String toUTCString(LocalTime localTime) {
        // Convert the local time to a ZonedDateTime in the local time zone
        ZonedDateTime localZonedDateTime = localTime.atDate(LocalDate.now()).atZone(ZoneId.systemDefault());

        // Convert to the UTC time zone
        ZonedDateTime utcZonedDateTime = localZonedDateTime.withZoneSameInstant(ZoneId.of("UTC"));

        // Format the UTC time to a string in "HH:mm" format
        return utcZonedDateTime.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"));
    }


    public static LocalTime fromUTCString(String utcString) {
        // Parse the time string to a LocalTime object
        LocalTime utcTime = LocalTime.parse(utcString);

        // Convert to a ZonedDateTime in the UTC time zone
        ZonedDateTime utcZonedDateTime = utcTime.atDate(LocalDate.now(ZoneId.of("UTC"))).atZone(ZoneId.of("UTC"));

        // Convert to the local time zone
        ZonedDateTime localZonedDateTime = utcZonedDateTime.withZoneSameInstant(ZoneId.systemDefault());

        // Get the local date (you can also get the local time or other details if needed)
        return localZonedDateTime.toLocalTime();
    }

    /**
     * Checks if the given time is between the start and end times (inclusive).
     *
     * @param fromTime the start time (inclusive)
     * @param toTime   the end time (inclusive)
     * @param time     the time to check
     * @return true if the time is between fromTime and toTime, inclusive; false otherwise
     */
    public static boolean isBetween(LocalTime fromTime, LocalTime toTime, LocalTime time) {
        if (fromTime.isBefore(toTime) || fromTime.equals(toTime)) {
            // Normal case: fromTime is before or equal to toTime
            return !time.isBefore(fromTime) && !time.isAfter(toTime);
        } else {
            // Special case: the period spans midnight (e.g., 23:00 to 02:00)
            return !time.isBefore(fromTime) || !time.isAfter(toTime);
        }
    }
}
