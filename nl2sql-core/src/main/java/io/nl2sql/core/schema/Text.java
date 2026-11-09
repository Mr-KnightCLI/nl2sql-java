package io.nl2sql.core.schema;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Word tokens with naive singularisation, used for lexical schema and example retrieval. */
public final class Text {
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    private Text() {
    }

    public static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT).replace('_', ' '));
        while (m.find()) {
            String w = m.group();
            out.add(w);
            if (w.length() > 3 && w.endsWith("s")) out.add(w.substring(0, w.length() - 1));
            if (w.length() > 4 && w.endsWith("ies")) out.add(w.substring(0, w.length() - 3) + "y");
        }
        return out;
    }
}
