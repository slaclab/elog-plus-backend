package edu.stanford.slac.elog_plus.utility;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

import java.text.Normalizer;

import static edu.stanford.slac.elog_plus.api.v1.mapper.EntryMapper.ELOG_ENTRY_REF;

public class StringUtilities {
    static public String tagNameNormalization(String tagName) {
        return Normalizer
                .normalize(
                        tagName.trim().toLowerCase(),
                        Normalizer.Form.NFD
                ).replaceAll("[^\\p{ASCII}]", "")
                .replaceAll(" ", "-");
    }

    static public String shiftNameNormalization(String tagName) {
        return Normalizer
                .normalize(
                        tagName.trim(),
                        Normalizer.Form.NFD
                ).replaceAll("[^\\p{ASCII}]", "");
    }
    static public String tokenNameNormalization(String tagName) {
        return Normalizer
                .normalize(
                        tagName.trim(),
                        Normalizer.Form.NFD
                ).replaceAll("[^\\p{ASCII}]", "");
    }
    public static String sanitizeEntryTitle(String title) {
        return Jsoup.clean(title, Safelist.basicWithImages());
    }

    public static String sanitizeEntryText(String text) {
        Safelist safelist = Safelist.basic();
        safelist.addTags("h1", "h2", "h3", "h4", ELOG_ENTRY_REF);
        return Jsoup.clean(text, safelist);
    }

    public static String logbookNameNormalization(String name) {
        return Normalizer
                .normalize(
                        name.trim().toLowerCase(),
                        Normalizer.Form.NFD
                ).replaceAll("[^\\p{ASCII}]", "")
                .replaceAll(" ", "-");
    }

    public static String authenticationTokenNormalization(String name) {
        return Normalizer
                .normalize(
                        name.trim().toLowerCase(),
                        Normalizer.Form.NFD
                ).replaceAll("[^\\p{ASCII}]", "")
                .replaceAll(" ", "-");
    }
}
