package it.andrea.telecomandotv;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Interpreta frasi in italiano (dal riconoscimento vocale) e le traduce in comandi per il TV. */
public final class VoiceCommands {

    private VoiceCommands() { }

    public enum Type { KEYS, APP, POWER_ON, POWER_OFF, TEXT }

    public static class Action {
        public final Type type;
        public final List<String> keys;
        public final SamsungTv.AppInfo app;
        public final String text;
        public final String feedback;

        Action(Type type, List<String> keys, SamsungTv.AppInfo app, String text, String feedback) {
            this.type = type;
            this.keys = keys;
            this.app = app;
            this.text = text;
            this.feedback = feedback;
        }
    }

    private static final Map<String, String> NUMBERS = new HashMap<>();
    private static final String[] NUM_WORDS = {"zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette",
            "otto", "nove", "dieci", "undici", "dodici", "tredici", "quattordici", "quindici", "sedici",
            "diciassette", "diciotto", "diciannove", "venti"};

    /** Sinonimi per riconoscere le app più comuni anche se il nome sul TV è diverso. */
    private static final String[][] APP_ALIASES = {
            {"youtube", "youtube", "you tube", "tubo"},
            {"netflix", "netflix", "netflics"},
            {"prime video", "prime", "amazon", "prime video", "amazon prime"},
            {"disney+", "disney", "disney plus", "disney piu"},
            {"spotify", "spotify"},
            {"internet", "internet", "browser", "navigatore"},
            {"raiplay", "rai play", "raiplay"},
            {"mediaset", "mediaset", "mediaset infinity", "infinity"},
            {"dazn", "dazn", "dazzn", "da zone"},
            {"now", "now tv", "now"},
    };

    static {
        for (int i = 0; i < NUM_WORDS.length; i++) NUMBERS.put(NUM_WORDS[i], String.valueOf(i));
        NUMBERS.put("un", "1");
        NUMBERS.put("una", "1");
        NUMBERS.put("trenta", "30");
        NUMBERS.put("quaranta", "40");
        NUMBERS.put("cinquanta", "50");
    }

    /** Prova tutte le trascrizioni proposte dal riconoscitore; restituisce la prima che è un comando. */
    public static Action parseBest(List<String> candidates, List<SamsungTv.AppInfo> apps) {
        for (String c : candidates) {
            Action a = parse(c, apps);
            if (a != null) return a;
        }
        return null;
    }

    public static Action parse(String phrase, List<SamsungTv.AppInfo> apps) {
        String s = normalize(phrase);
        if (s.isEmpty()) return null;
        Matcher m;

        // ---- scrivere / cercare testo
        m = Pattern.compile("(?iu)^\\W*(?:\\S+\\s+){0,2}?(?:scrivi|scrivere|digita|cerca|cercare|ricerca|trova|trovami|cercami)\\s+(.+)$")
                .matcher(phrase.trim());
        if (m.find()) {
            String t = m.group(1).trim();
            return text(t, "Scrivo: " + t);
        }

        // ---- accensione / spegnimento
        if (s.matches("^(spegni|spegnere|spento|off|vai in standby|standby)( (la|il) (tv|televisione|televisore|tivu))?$")
                || s.matches("^spegni (tv|televisione|televisore|tivu)$")) {
            return new Action(Type.POWER_OFF, null, null, null, "Spengo il TV");
        }
        if (s.matches("^(accendi|accendere|on)( (la|il) (tv|televisione|televisore|tivu))?$")
                || s.matches("^accendi (tv|televisione|televisore|tivu)$")) {
            return new Action(Type.POWER_ON, null, null, null, "Accendo il TV");
        }

        // ---- volume
        if (s.matches(".*\\b(togli|disattiva|attiva|riattiva|metti)?\\s*(il )?(muto|audio|silenzio)\\b.*")
                && !s.contains("volume") && !s.contains("descrizione")) {
            return keys("Muto", "KEY_MUTE");
        }
        boolean volUp = s.matches(".*\\b(alza|aumenta|piu forte|volume su|volume piu|volume \\+|piu volume)\\b.*");
        boolean volDown = s.matches(".*\\b(abbassa|diminuisci|riduci|piu piano|volume giu|volume meno|meno volume)\\b.*");
        if (volUp || volDown) {
            int n = clamp(firstNumber(s, 3), 1, 30);
            return keys((volUp ? "Volume +" : "Volume −") + n, repeat(volUp ? "KEY_VOLUP" : "KEY_VOLDOWN", n));
        }

        // ---- canali
        m = Pattern.compile("^(?:metti |vai |sintonizza |passa )?(?:il |al |sul |su )?(?:canale|programma) (?:numero )?(\\d{1,4})$")
                .matcher(s);
        if (m.find()) {
            List<String> k = new ArrayList<>();
            for (char c : m.group(1).toCharArray()) k.add("KEY_" + c);
            k.add("KEY_ENTER");
            return new Action(Type.KEYS, k, null, null, "Canale " + m.group(1));
        }
        if (s.matches(".*\\b(canale precedente|ultimo canale|torna al canale|canale di prima)\\b.*")) {
            return keys("Canale precedente", "KEY_PRECH");
        }
        if (s.matches(".*\\bcanale\\b.*\\b(su|successivo|avanti|dopo|piu|prossimo)\\b.*")
                || s.matches(".*\\b(prossimo|successivo) canale\\b.*")) {
            int n = clamp(firstNumber(s, 1), 1, 20);
            return keys("Canale su", repeat("KEY_CHUP", n));
        }
        if (s.matches(".*\\bcanale\\b.*\\b(giu|indietro|meno|prima)\\b.*")) {
            int n = clamp(firstNumber(s, 1), 1, 20);
            return keys("Canale giù", repeat("KEY_CHDOWN", n));
        }
        if (s.matches(".*\\b(lista canali|elenco canali)\\b.*")) return keys("Lista canali", "KEY_CH_LIST");

        // ---- sorgenti
        m = Pattern.compile("\\bhdmi\\s*(\\d)\\b").matcher(s);
        if (m.find()) return keys("HDMI " + m.group(1), "KEY_HDMI" + m.group(1));
        if (s.matches(".*\\b(sorgente|ingresso|sorgenti)\\b.*")) return keys("Sorgente", "KEY_SOURCE");
        if (s.matches(".*\\b(digitale terrestre|antenna|tv normale)\\b.*")) return keys("TV", "KEY_TV");

        // ---- riproduzione
        if (s.matches(".*\\b(indietro veloce|riavvolgi|torna indietro veloce)\\b.*")) return keys("Indietro veloce", "KEY_REWIND");
        if (s.matches(".*\\b(avanti veloce|avanza|vai avanti veloce)\\b.*")) return keys("Avanti veloce", "KEY_FF");
        if (s.matches(".*\\b(pausa|metti in pausa|fermati un attimo)\\b.*")) return keys("Pausa", "KEY_PAUSE");
        if (s.matches("^(play|riproduci|riprendi|continua|fai partire|parti)$")) return keys("Play", "KEY_PLAY");
        if (s.matches("^(stop|ferma|interrompi)$")) return keys("Stop", "KEY_STOP");

        // ---- app
        m = Pattern.compile("^(?:apri|avvia|lancia|metti|vai su|vai a|fai partire|guarda|voglio vedere)\\s+(?:l'app\\s+|app\\s+|l applicazione\\s+|applicazione\\s+)?(.+)$")
                .matcher(s);
        if (m.find()) {
            String target = m.group(1).trim();
            SamsungTv.AppInfo app = findApp(target, apps);
            if (app != null) return new Action(Type.APP, null, app, null, "Apro " + app.name);
        }
        SamsungTv.AppInfo direct = findApp(s, apps);
        if (direct != null && s.split(" ").length <= 3) return new Action(Type.APP, null, direct, null, "Apro " + direct.name);

        // ---- navigazione (anche "giù 3 volte", "destra di due")
        String[][] nav = {
                {"^(?:vai )?(?:su|sopra|in alto)\\b", "KEY_UP", "Su"},
                {"^(?:vai )?(?:giu|sotto|in basso)\\b", "KEY_DOWN", "Giù"},
                {"^(?:vai )?(?:a )?(?:destra)\\b", "KEY_RIGHT", "Destra"},
                {"^(?:vai )?(?:a )?(?:sinistra)\\b", "KEY_LEFT", "Sinistra"},
        };
        for (String[] n : nav) {
            if (Pattern.compile(n[0]).matcher(s).find()) {
                int c = clamp(firstNumber(s, 1), 1, 20);
                return keys(n[2] + (c > 1 ? " ×" + c : ""), repeat(n[1], c));
            }
        }
        if (s.matches("^(ok|okay|conferma|invio|seleziona|vai|si|entra)$")) return keys("OK", "KEY_ENTER");
        if (s.matches("^(indietro|torna indietro|annulla)$")) return keys("Indietro", "KEY_RETURN");
        if (s.matches("^(home|casa|menu principale|schermata principale|smart hub)$")) return keys("Home", "KEY_HOME");
        if (s.matches("^(esci|chiudi)$")) return keys("Esci", "KEY_EXIT");
        if (s.matches("^(menu|impostazioni)$")) return keys("Menu", "KEY_MENU");
        if (s.matches(".*\\b(guida|guida tv|programmi di stasera)\\b.*")) return keys("Guida", "KEY_GUIDE");
        if (s.matches("^(info|informazioni)$")) return keys("Info", "KEY_INFO");
        if (s.matches(".*\\b(sottotitoli)\\b.*")) return keys("Sottotitoli", "KEY_SUB_TITLE");
        if (s.matches(".*\\b(televideo)\\b.*")) return keys("Televideo", "KEY_TTX_MIX");

        return null;
    }

    // ------------------------------------------------------------------ helper

    static String normalize(String in) {
        String s = in.toLowerCase(Locale.ITALIAN).trim();
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.replaceAll("[\\p{Punct}&&[^'+]]", " ").replace("'", " ");
        s = s.replaceAll("\\b(per favore|perfavore|per piacere|grazie|ehi|dai|puoi|potresti)\\b", " ");
        StringBuilder b = new StringBuilder();
        for (String w : s.split("\\s+")) {
            if (w.isEmpty()) continue;
            String n = NUMBERS.get(w);
            if (b.length() > 0) b.append(' ');
            b.append(n != null ? n : w);
        }
        return b.toString().replaceAll("\\b(la tv|il tv|la televisione|il televisore|la tivu)$", "").trim();
    }

    static SamsungTv.AppInfo findApp(String target, List<SamsungTv.AppInfo> apps) {
        String t = normalize(target).replaceAll("^(la|il|lo|l)\\s+", "").trim();
        if (t.isEmpty() || apps == null) return null;
        String compactT = t.replace(" ", "");
        // 1) nome dell'app contenuto nella frase o viceversa
        for (SamsungTv.AppInfo a : apps) {
            String n = normalize(a.name).replace(" ", "");
            if (n.isEmpty()) continue;
            if (n.equals(compactT) || compactT.contains(n) || (compactT.length() >= 4 && n.contains(compactT))) return a;
        }
        // 2) sinonimi
        for (String[] alias : APP_ALIASES) {
            for (int i = 1; i < alias.length; i++) {
                if (t.equals(alias[i]) || t.startsWith(alias[i] + " ") || t.contains(alias[i])) {
                    String key = alias[0].replace("+", "");
                    for (SamsungTv.AppInfo a : apps) {
                        String n = normalize(a.name).replace("+", "");
                        if (n.contains(key) || n.replace(" ", "").contains(key.replace(" ", ""))) return a;
                    }
                }
            }
        }
        return null;
    }

    private static int firstNumber(String s, int def) {
        Matcher m = Pattern.compile("\\b(\\d{1,3})\\b").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static List<String> repeat(String key, int n) {
        return new ArrayList<>(Collections.nCopies(n, key));
    }

    private static Action keys(String feedback, String key) {
        return keys(feedback, repeat(key, 1));
    }

    private static Action keys(String feedback, List<String> k) {
        return new Action(Type.KEYS, k, null, null, feedback);
    }

    private static Action text(String t, String feedback) {
        return new Action(Type.TEXT, null, null, t, feedback);
    }
}
