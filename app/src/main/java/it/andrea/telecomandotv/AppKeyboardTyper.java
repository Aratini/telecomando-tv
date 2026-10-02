package it.andrea.telecomandotv;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Batte" un testo sulle tastiere a schermo delle app (che non accettano testo dal telefono)
 * muovendo le frecce e premendo OK, come faresti tu con il telecomando.
 */
public final class AppKeyboardTyper {

    private AppKeyboardTyper() { }

    public enum Layout { YOUTUBE, NETFLIX, PRIME, RAIPLAY }

    public static class Plan {
        public final List<String> keys = new ArrayList<>();
        public final StringBuilder skipped = new StringBuilder();
    }

    // ------------------------------------------------------------------ YouTube
    //   A B C D E F G  [⌫]          cursore iniziale sulla A
    //   H I J K L M N  [&123]
    //   O P Q R S T U  [🌐]
    //   V W X Y Z - '
    //   [SPAZIO] [CANCELLA] [CERCA]  ← si raggiungono sempre dalla W (sta sopra SPAZIO)
    private static final String[] YT_ROWS = {"abcdefg", "hijklmn", "opqrstu", "vwxyz-'"};

    // ------------------------------------------------------------------ Netflix
    //   [  SPAZIO  ] [    ⌫    ]      cursore iniziale su SPAZIO
    //   a b c d e f                  SPAZIO si raggiunge dalla b, ⌫ dalla e
    //   g h i j k l
    //   m n o p q r
    //   s t u v w x
    //   y z 1 2 3 4
    //   5 6 7 8 9 0
    private static final String[] NF_ROWS = {"abcdef", "ghijkl", "mnopqr", "stuvwx", "yz1234", "567890"};

    // ------------------------------------------------------------------ Prime Video
    //   a b c d e f                  cursore iniziale sulla a
    //   g h i j k l
    //   m n o p q r
    //   s t u v w x
    //   y z à è é ì
    //   ò ù 1 2 3 4
    //   5 6 7 8 9 0
    //   '  [ SPAZIO ]  [⌫]           ' sotto il 5, SPAZIO sotto il 7, ⌫ sotto il 9
    private static final String[] PV_ROWS = {"abcdef", "ghijkl", "mnopqr", "stuvwx", "yzàèéì", "òù1234", "567890"};

    // ------------------------------------------------------------------ RaiPlay
    //   a b c d e f                  cursore iniziale sulla a
    //   g h i j k l
    //   m n o p q r
    //   s t u v w x
    //   y z 1 2 3 4
    //   5 6 7 8 9 0
    //   [⌫] [SPAZIO]      [CERCA]    ⌫ sotto il 5, SPAZIO sotto il 6, CERCA sotto il 9
    private static final String[] RAI_ROWS = {"abcdef", "ghijkl", "mnopqr", "stuvwx", "yz1234", "567890"};

    public static Plan plan(Layout layout, String text, boolean clearFirst, boolean pressSearch) {
        if (layout == Layout.PRIME) return prime(text, clearFirst);
        String t = Normalizer.normalize(text.toLowerCase(Locale.ITALIAN), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('’', '\'')
                .replaceAll("\\s+", " ")
                .trim();
        if (layout == Layout.RAIPLAY) return raiplay(t, clearFirst, pressSearch);
        return layout == Layout.NETFLIX ? netflix(t, clearFirst) : youtube(t, clearFirst, pressSearch);
    }

    private static Plan youtube(String t, boolean clearFirst, boolean pressSearch) {
        Plan p = new Plan();
        int[] pos = {0, 0};
        if (clearFirst) {
            moveTo(p.keys, pos, 3, 1);
            add(p.keys, "KEY_DOWN", "KEY_RIGHT", "KEY_ENTER", "KEY_LEFT", "KEY_UP"); // CANCELLA, torna sulla W
        }
        for (char ch : t.toCharArray()) {
            if (ch == ' ') {
                moveTo(p.keys, pos, 3, 1);
                add(p.keys, "KEY_DOWN", "KEY_ENTER", "KEY_UP");
                continue;
            }
            int[] rc = find(YT_ROWS, ch);
            if (rc == null) {
                skip(p, ch);
                continue;
            }
            moveTo(p.keys, pos, rc[0], rc[1]);
            p.keys.add("KEY_ENTER");
        }
        if (pressSearch) {
            moveTo(p.keys, pos, 3, 1);
            add(p.keys, "KEY_DOWN", "KEY_RIGHT", "KEY_RIGHT", "KEY_ENTER");
        } else {
            moveTo(p.keys, pos, 0, 0);
        }
        return p;
    }

    private static Plan netflix(String t, boolean clearFirst) {
        Plan p = new Plan();
        int[] pos = {0, 1};
        p.keys.add("KEY_DOWN"); // da SPAZIO scende sulla b
        if (clearFirst) {
            moveTo(p.keys, pos, 0, 4);
            p.keys.add("KEY_UP"); // ⌫
            for (int i = 0; i < 25; i++) p.keys.add("KEY_ENTER");
            p.keys.add("KEY_DOWN"); // torna sulla e
        }
        boolean pendingSpace = false;
        for (char ch : t.toCharArray()) {
            if (ch == ' ') {
                pendingSpace = true;
                continue;
            }
            int[] rc = find(NF_ROWS, ch);
            if (rc == null) {
                skip(p, ch);
                continue;
            }
            if (pendingSpace) {
                moveTo(p.keys, pos, 0, 1);
                add(p.keys, "KEY_UP", "KEY_ENTER", "KEY_DOWN");
                pendingSpace = false;
            }
            moveTo(p.keys, pos, rc[0], rc[1]);
            p.keys.add("KEY_ENTER");
        }
        // Netflix cerca mentre scrivi: riporto il cursore su SPAZIO, dove la tastiera parte sempre
        moveTo(p.keys, pos, 0, 1);
        p.keys.add("KEY_UP");
        return p;
    }

    private static Plan prime(String text, boolean clearFirst) {
        Plan p = new Plan();
        int[] pos = {0, 0};
        String t = Normalizer.normalize(text.toLowerCase(Locale.ITALIAN), Normalizer.Form.NFC)
                .replace('’', '\'').replaceAll("\\s+", " ").trim();
        if (clearFirst) {
            moveTo(p.keys, pos, 6, 4);
            p.keys.add("KEY_DOWN"); // ⌫
            for (int i = 0; i < 25; i++) p.keys.add("KEY_ENTER");
            add(p.keys, "KEY_LEFT", "KEY_UP"); // SPAZIO, poi su sul 7
            pos[0] = 6;
            pos[1] = 2;
        }
        for (char ch : t.toCharArray()) {
            if (ch == ' ') {
                moveTo(p.keys, pos, 6, 2);
                add(p.keys, "KEY_DOWN", "KEY_ENTER", "KEY_UP");
                continue;
            }
            if (ch == '\'') {
                moveTo(p.keys, pos, 6, 0);
                add(p.keys, "KEY_DOWN", "KEY_ENTER", "KEY_UP");
                continue;
            }
            int[] rc = find(PV_ROWS, ch);
            if (rc == null) {
                String plain = Normalizer.normalize(String.valueOf(ch), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
                if (plain.length() == 1) rc = find(PV_ROWS, plain.charAt(0));
            }
            if (rc == null) {
                skip(p, ch);
                continue;
            }
            moveTo(p.keys, pos, rc[0], rc[1]);
            p.keys.add("KEY_ENTER");
        }
        moveTo(p.keys, pos, 0, 0); // Prime cerca mentre scrivi: lascio il cursore sulla a
        return p;
    }

    private static Plan raiplay(String t, boolean clearFirst, boolean pressSearch) {
        Plan p = new Plan();
        int[] pos = {0, 0};
        if (clearFirst) {
            moveTo(p.keys, pos, 5, 0);
            p.keys.add("KEY_DOWN"); // ⌫
            for (int i = 0; i < 25; i++) p.keys.add("KEY_ENTER");
            p.keys.add("KEY_UP");   // torna sul 5
        }
        for (char ch : t.toCharArray()) {
            if (ch == ' ') {
                moveTo(p.keys, pos, 5, 1);
                add(p.keys, "KEY_DOWN", "KEY_ENTER", "KEY_UP");
                continue;
            }
            int[] rc = find(RAI_ROWS, ch);
            if (rc == null) {
                skip(p, ch);
                continue;
            }
            moveTo(p.keys, pos, rc[0], rc[1]);
            p.keys.add("KEY_ENTER");
        }
        if (pressSearch) {
            moveTo(p.keys, pos, 5, 4);
            add(p.keys, "KEY_DOWN", "KEY_ENTER"); // CERCA
        } else {
            moveTo(p.keys, pos, 0, 0);
        }
        return p;
    }

    private static int[] find(String[] rows, char ch) {
        for (int i = 0; i < rows.length; i++) {
            int idx = rows[i].indexOf(ch);
            if (idx >= 0) return new int[]{i, idx};
        }
        return null;
    }

    private static void skip(Plan p, char ch) {
        if (p.skipped.indexOf(String.valueOf(ch)) < 0) p.skipped.append(ch);
    }

    private static void add(List<String> keys, String... k) {
        for (String s : k) keys.add(s);
    }

    private static void moveTo(List<String> keys, int[] pos, int r, int c) {
        while (pos[1] < c) { keys.add("KEY_RIGHT"); pos[1]++; }
        while (pos[1] > c) { keys.add("KEY_LEFT"); pos[1]--; }
        while (pos[0] < r) { keys.add("KEY_DOWN"); pos[0]++; }
        while (pos[0] > r) { keys.add("KEY_UP"); pos[0]--; }
    }
}
