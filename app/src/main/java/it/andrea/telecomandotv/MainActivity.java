package it.andrea.telecomandotv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

public class MainActivity extends Activity implements SamsungTv.Callback {

    // Palette
    private static final int BG = 0xFF101317;
    private static final int SURFACE = 0xFF1B2027;
    private static final int KEY = 0xFF262D36;
    private static final int KEY_DARK = 0xFF1F252D;
    private static final int ACCENT = 0xFF3B7BFF;
    private static final int POWER = 0xFFD8453B;
    private static final int TEXT = 0xFFECEFF3;
    private static final int MUTED = 0xFF8E98A6;
    private static final int OK_GREEN = 0xFF34C38F;
    private static final int AMBER = 0xFFF0B429;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private SamsungTv tv;

    private View statusDot;
    private TextView statusText;
    private FrameLayout content;
    private final List<TextView> tabs = new ArrayList<>();
    private final List<View> pages = new ArrayList<>();
    private LinearLayout appsBox;
    private List<SamsungTv.AppInfo> apps = defaultApps();
    private boolean pointerMode = false;

    // Tastiera e voce
    private static final int REQ_VOICE_COMMAND = 101;
    private static final int REQ_VOICE_DICTATE = 102;
    private boolean keepConnection = false;   // non disconnettere mentre è aperto il riconoscimento vocale
    private boolean tvKeyboardOpen = false;   // il TV sta mostrando la sua tastiera a schermo
    private AlertDialog keyboardDialog;

    // Icone delle app (in memoria + salvate nella cache del telefono)
    private final Map<String, Bitmap> iconCache = new HashMap<>();
    private final Map<String, ImageView> iconViews = new HashMap<>();
    private EditText keyboardField;

    // ------------------------------------------------------------------ ciclo di vita

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        tv = new SamsungTv(prefs, this);
        setContentView(buildRoot());
        showTab(0);
        if (prefs.getString("host", null) == null) {
            ui.post(() -> showSettings(true));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        keepConnection = false;
        String host = prefs.getString("host", null);
        if (host != null) tv.connect(host);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!keepConnection) tv.disconnect();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (prefs.getBoolean("volume_keys", true) && tv.getHost() != null) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                tv.key("KEY_VOLUP");
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                tv.key("KEY_VOLDOWN");
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ callback TV

    @Override
    public void onState(SamsungTv.State state, String message) {
        int color;
        switch (state) {
            case CONNECTED:
                color = OK_GREEN;
                break;
            case DISCONNECTED:
                color = POWER;
                break;
            default:
                color = AMBER;
                break;
        }
        ((GradientDrawable) statusDot.getBackground()).setColor(color);
        if (state == SamsungTv.State.CONNECTED) {
            statusText.setText(prefs.getString("name", "TV Samsung") + " · connesso");
            fetchDeviceInfoIfNeeded();
        } else {
            statusText.setText(message);
        }
    }

    @Override
    public void onApps(List<SamsungTv.AppInfo> list) {
        if (list != null && !list.isEmpty()) {
            apps = list;
            fillApps();
            List<SamsungTv.AppInfo> missing = new ArrayList<>();
            for (SamsungTv.AppInfo a : list) if (loadIcon(a.id) == null) missing.add(a);
            tv.requestIcons(missing);
        }
    }

    @Override
    public void onAppIcon(String appId, byte[] image) {
        Bitmap bmp = BitmapFactory.decodeByteArray(image, 0, image.length);
        if (bmp == null) return;
        iconCache.put(appId, bmp);
        try (FileOutputStream fo = new FileOutputStream(iconFile(appId))) {
            fo.write(image);
        } catch (Exception ignored) {
        }
        ImageView iv = iconViews.get(appId);
        if (iv != null) {
            iv.setImageBitmap(bmp);
            iv.setBackground(null);
            View letter = (View) iv.getTag();
            if (letter != null) letter.setVisibility(View.GONE);
        }
    }

    private File iconFile(String appId) {
        File dir = new File(getCacheDir(), "icons");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, appId.replaceAll("[^A-Za-z0-9._-]", "_") + ".img");
    }

    private Bitmap loadIcon(String appId) {
        Bitmap b = iconCache.get(appId);
        if (b != null) return b;
        File f = iconFile(appId);
        if (f.exists()) {
            b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) iconCache.put(appId, b);
        }
        return b;
    }

    @Override
    public void onTvKeyboard(boolean open, String text) {
        boolean wasOpen = tvKeyboardOpen;
        tvKeyboardOpen = open;
        if (open) {
            if (keyboardDialog != null && keyboardDialog.isShowing()) {
                if (keyboardField != null && keyboardField.getText().length() == 0 && !text.isEmpty()) {
                    keyboardField.setText(text);
                    keyboardField.setSelection(text.length());
                }
            } else if (!wasOpen && prefs.getBoolean("auto_keyboard", true) && hasWindowFocus()) {
                showKeyboard(text);
            }
        } else if (keyboardDialog != null && keyboardDialog.isShowing() && prefs.getBoolean("auto_keyboard", true)) {
            keyboardDialog.dismiss();
        }
    }

    private void fetchDeviceInfoIfNeeded() {
        String host = tv.getHost();
        if (host == null || (!prefs.getString("mac", "").isEmpty() && prefs.contains("name"))) return;
        new Thread(() -> {
            JSONObject info = SamsungTv.fetchInfo(host, 2000);
            if (info == null) return;
            JSONObject dev = info.optJSONObject("device");
            String mac = dev != null ? dev.optString("wifiMac", "") : "";
            String name = info.optString("name", dev != null ? dev.optString("name", "") : "");
            SharedPreferences.Editor ed = prefs.edit();
            if (!mac.isEmpty()) ed.putString("mac", mac);
            if (!name.isEmpty()) ed.putString("name", name);
            ed.apply();
            if (!name.isEmpty()) ui.post(() -> {
                if (tv.isConnected()) statusText.setText(name + " · connesso");
            });
        }).start();
    }

    // ------------------------------------------------------------------ struttura

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(10), dp(12), dp(8));

        // Intestazione: stato + tastiera + impostazioni
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(POWER);
        statusDot.setBackground(dot);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dlp.rightMargin = dp(10);
        header.addView(statusDot, dlp);

        statusText = new TextView(this);
        statusText.setTextColor(MUTED);
        statusText.setTextSize(13);
        statusText.setMaxLines(3);
        statusText.setText("Non collegato");
        statusText.setOnClickListener(v -> {
            String host = prefs.getString("host", null);
            if (host == null) showSettings(true);
            else if (!tv.isConnected()) tv.connect(host);
        });
        header.addView(statusText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView mic = button("🎤", ACCENT, 18);
        mic.setOnClickListener(v -> {
            haptic(v);
            startVoice(REQ_VOICE_COMMAND);
        });
        mic.setOnLongClickListener(v -> {
            showVoiceHelp();
            return true;
        });
        header.addView(mic, squareLp(44));
        TextView kbd = button("Aa", KEY, 15);
        kbd.setOnClickListener(v -> {
            haptic(v);
            showKeyboard("");
        });
        header.addView(kbd, squareLp(44));
        TextView settings = button("⚙", KEY, 18);
        settings.setOnClickListener(v -> showSettings(false));
        header.addView(settings, squareLp(44));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        // Riga fissa: accensione, sorgente, muto
        TextView power = button("ON / OFF", POWER, 15);
        power.setOnClickListener(v -> {
            haptic(v);
            onPowerPressed();
        });
        root.addView(row(56, power,
                keyBtn("SORGENTE", "KEY_SOURCE", KEY, 14, false),
                keyBtn("MUTO", "KEY_MUTE", KEY, 14, false)));

        // Schede
        LinearLayout tabBar = new LinearLayout(this);
        String[] names = {"Tasti", "Touchpad", "App", "Numeri"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(14);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setOnClickListener(v -> showTab(idx));
            tabs.add(t);
            tabBar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        tlp.topMargin = dp(8);
        tlp.bottomMargin = dp(6);
        tabBar.setBackground(rounded(SURFACE, 12));
        root.addView(tabBar, tlp);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        pages.add(scroll(buildRemotePage()));
        pages.add(buildTouchpadPage());
        pages.add(scroll(buildAppsPage()));
        pages.add(scroll(buildNumbersPage()));
        for (View p : pages) {
            content.addView(p, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return root;
    }

    private void showTab(int idx) {
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).setVisibility(i == idx ? View.VISIBLE : View.GONE);
            TextView t = tabs.get(i);
            t.setTextColor(i == idx ? TEXT : MUTED);
            t.setBackground(i == idx ? rounded(ACCENT, 12) : null);
        }
    }

    // ------------------------------------------------------------------ pagina Tasti

    private View buildRemotePage() {
        LinearLayout p = column();

        // Croce direzionale
        LinearLayout dpad = column();
        dpad.setPadding(dp(28), dp(4), dp(28), dp(4));
        dpad.addView(row(66, new Space(this), keyBtn("▲", "KEY_UP", KEY, 20, true), new Space(this)));
        TextView ok = keyBtn("OK", "KEY_ENTER", ACCENT, 18, false);
        ok.setBackground(ripple(ACCENT, 40));
        dpad.addView(row(66, keyBtn("◀", "KEY_LEFT", KEY, 20, true), ok,
                keyBtn("▶", "KEY_RIGHT", KEY, 20, true)));
        dpad.addView(row(66, new Space(this), keyBtn("▼", "KEY_DOWN", KEY, 20, true), new Space(this)));
        p.addView(dpad);

        p.addView(row(50,
                keyBtn("↩ INDIETRO", "KEY_RETURN", KEY, 13, false),
                keyBtn("HOME", "KEY_HOME", KEY, 13, false),
                keyBtn("ESCI", "KEY_EXIT", KEY, 13, false)));

        // Bilancieri volume / canale con tasti centrali
        LinearLayout vol = column();
        vol.addView(single(56, keyBtn("+", "KEY_VOLUP", KEY, 24, true)));
        vol.addView(single(28, label("VOL")));
        vol.addView(single(56, keyBtn("−", "KEY_VOLDOWN", KEY, 24, true)));

        LinearLayout mid = column();
        mid.addView(single(44, keyBtn("MENU", "KEY_MENU", KEY_DARK, 12, false)));
        mid.addView(single(44, keyBtn("INFO", "KEY_INFO", KEY_DARK, 12, false)));
        mid.addView(single(44, keyBtn("STRUMENTI", "KEY_TOOLS", KEY_DARK, 12, false)));

        LinearLayout ch = column();
        ch.addView(single(56, keyBtn("▲", "KEY_CHUP", KEY, 18, true)));
        ch.addView(single(28, label("CH")));
        ch.addView(single(56, keyBtn("▼", "KEY_CHDOWN", KEY, 18, true)));

        LinearLayout rockers = new LinearLayout(this);
        rockers.addView(vol, weightLp());
        rockers.addView(mid, weightLp());
        rockers.addView(ch, weightLp());
        LinearLayout.LayoutParams rlp = matchWrap();
        rlp.topMargin = dp(6);
        p.addView(rockers, rlp);

        p.addView(row(48,
                keyBtn("◀◀", "KEY_REWIND", KEY_DARK, 14, false),
                keyBtn("▶", "KEY_PLAY", KEY_DARK, 16, false),
                keyBtn("❚❚", "KEY_PAUSE", KEY_DARK, 14, false),
                keyBtn("■", "KEY_STOP", KEY_DARK, 16, false),
                keyBtn("▶▶", "KEY_FF", KEY_DARK, 14, false)));

        p.addView(row(38,
                keyBtn("", "KEY_RED", 0xFFD64545, 12, false),
                keyBtn("", "KEY_GREEN", 0xFF3FAE5A, 12, false),
                keyBtn("", "KEY_YELLOW", 0xFFE3B72F, 12, false),
                keyBtn("", "KEY_CYAN", 0xFF3D78D8, 12, false)));
        return p;
    }

    // ------------------------------------------------------------------ pagina Touchpad

    private View buildTouchpadPage() {
        LinearLayout p = column();

        TextView pointerBtn = button("Puntatore", KEY, 14);
        TextView gestBtn = button("Gesti (frecce)", KEY, 14);
        Runnable refresh = () -> {
            pointerBtn.setBackground(ripple(pointerMode ? ACCENT : KEY, 12));
            gestBtn.setBackground(ripple(pointerMode ? KEY : ACCENT, 12));
        };
        pointerBtn.setOnClickListener(v -> {
            pointerMode = true;
            refresh.run();
        });
        gestBtn.setOnClickListener(v -> {
            pointerMode = false;
            refresh.run();
        });
        refresh.run();
        p.addView(row(46, gestBtn, pointerBtn));

        TextView pad = new TextView(this);
        pad.setGravity(Gravity.CENTER);
        pad.setTextColor(MUTED);
        pad.setTextSize(13);
        pad.setPadding(dp(24), dp(24), dp(24), dp(24));
        pad.setText("Gesti: scorri per muoverti, tocca per OK\n\n"
                + "Puntatore: trascina per muovere, tocca per cliccare\n(funziona nel browser del TV e in alcune app)");
        pad.setBackground(rounded(SURFACE, 18));
        pad.setOnTouchListener(new PadTouch());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        plp.setMargins(dp(4), dp(8), dp(4), dp(8));
        p.addView(pad, plp);

        p.addView(row(52,
                keyBtn("↩ INDIETRO", "KEY_RETURN", KEY, 13, false),
                keyBtn("HOME", "KEY_HOME", KEY, 13, false),
                keyBtn("−  VOL", "KEY_VOLDOWN", KEY, 13, true),
                keyBtn("VOL  +", "KEY_VOLUP", KEY, 13, true)));
        return p;
    }

    private class PadTouch implements View.OnTouchListener {
        private float downX, downY, lastX, lastY, anchorX, anchorY, accX, accY;
        private long downTime, lastSend;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = anchorX = x;
                    downY = lastY = anchorY = y;
                    accX = accY = 0;
                    downTime = SystemClock.uptimeMillis();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = x - lastX, dy = y - lastY;
                    lastX = x;
                    lastY = y;
                    if (Math.abs(x - downX) > dp(8) || Math.abs(y - downY) > dp(8)) moved = true;
                    float density = getResources().getDisplayMetrics().density;
                    if (pointerMode) {
                        accX += dx / density * 2.5f;
                        accY += dy / density * 2.5f;
                        long now = SystemClock.uptimeMillis();
                        if (now - lastSend >= 25 && (Math.abs(accX) >= 1 || Math.abs(accY) >= 1)) {
                            tv.mouseMove((int) accX, (int) accY);
                            accX -= (int) accX;
                            accY -= (int) accY;
                            lastSend = now;
                        }
                    } else {
                        float step = dp(60);
                        float ax = x - anchorX, ay = y - anchorY;
                        if (Math.abs(ax) >= step && Math.abs(ax) > Math.abs(ay)) {
                            tv.key(ax > 0 ? "KEY_RIGHT" : "KEY_LEFT");
                            haptic(v);
                            anchorX += ax > 0 ? step : -step;
                            anchorY = y;
                        } else if (Math.abs(ay) >= step) {
                            tv.key(ay > 0 ? "KEY_DOWN" : "KEY_UP");
                            haptic(v);
                            anchorY += ay > 0 ? step : -step;
                            anchorX = x;
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved && SystemClock.uptimeMillis() - downTime < 350) {
                        if (pointerMode) tv.mouseClick();
                        else tv.key("KEY_ENTER");
                        haptic(v);
                        v.performClick();
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    // ------------------------------------------------------------------ pagina App

    private View buildAppsPage() {
        LinearLayout p = column();
        TextView refresh = button("↻  Carica le app installate sul TV", KEY, 14);
        refresh.setOnClickListener(v -> {
            haptic(v);
            if (tv.isConnected()) {
                tv.requestApps();
                toast("Richiesta inviata al TV…");
            } else {
                toast("Collega prima il TV");
            }
        });
        p.addView(single(48, refresh));
        appsBox = column();
        p.addView(appsBox, matchWrap());
        fillApps();
        return p;
    }

    private void fillApps() {
        if (appsBox == null) return;
        appsBox.removeAllViews();
        iconViews.clear();
        for (int i = 0; i < apps.size(); i += 3) {
            View[] cells = new View[3];
            for (int c = 0; c < 3; c++) {
                cells[c] = i + c < apps.size() ? appButton(apps.get(i + c)) : new Space(this);
            }
            appsBox.addView(row(108, cells));
        }
    }

    /** Colore di riserva (quando l'icona vera non è ancora arrivata) per le app più note. */
    private static int brandColor(String name) {
        String n = name.toLowerCase();
        if (n.contains("youtube")) return 0xFFFF0000;
        if (n.contains("netflix")) return 0xFFE50914;
        if (n.contains("prime") || n.contains("amazon")) return 0xFF00A8E1;
        if (n.contains("disney")) return 0xFF113CCF;
        if (n.contains("spotify")) return 0xFF1DB954;
        if (n.contains("rai")) return 0xFF0A5BB3;
        if (n.contains("mediaset") || n.contains("infinity")) return 0xFF0C2340;
        if (n.contains("dazn")) return 0xFF1A1A1A;
        if (n.contains("now")) return 0xFF00B5AD;
        if (n.contains("internet") || n.contains("browser")) return 0xFF5B6CFF;
        int[] pal = {0xFF8E44AD, 0xFF16A085, 0xFFD35400, 0xFF2C3E50, 0xFFC0392B, 0xFF2980B9, 0xFF27AE60};
        return pal[Math.abs(name.hashCode()) % pal.length];
    }

    private View appButton(SamsungTv.AppInfo app) {
        LinearLayout tile = column();
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(4), dp(8), dp(4), dp(6));
        tile.setBackground(ripple(KEY, 14));
        tile.setClickable(true);
        tile.setFocusable(true);

        FrameLayout iconBox = new FrameLayout(this);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setClipToOutline(true);
        TextView letter = new TextView(this);
        letter.setGravity(Gravity.CENTER);
        letter.setTextColor(Color.WHITE);
        letter.setTextSize(22);
        letter.setTypeface(Typeface.DEFAULT_BOLD);
        String initial = app.name.trim().isEmpty() ? "?" : app.name.trim().substring(0, 1).toUpperCase();
        letter.setText(initial);
        iv.setTag(letter);

        Bitmap bmp = loadIcon(app.id);
        if (bmp != null) {
            iv.setImageBitmap(bmp);
            letter.setVisibility(View.GONE);
        } else {
            iv.setBackground(rounded(brandColor(app.name), 12));
        }
        iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        });
        iconBox.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        iconBox.addView(letter, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        iconViews.put(app.id, iv);
        tile.addView(iconBox, new LinearLayout.LayoutParams(dp(60), dp(60)));

        TextView name = new TextView(this);
        name.setText(app.name);
        name.setTextColor(TEXT);
        name.setTextSize(12);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = matchWrap();
        nlp.topMargin = dp(6);
        tile.addView(name, nlp);

        tile.setOnClickListener(v -> {
            haptic(v);
            tv.launchApp(app);
            toast("Avvio " + app.name + "…");
        });
        return tile;
    }

    private static List<SamsungTv.AppInfo> defaultApps() {
        List<SamsungTv.AppInfo> l = new ArrayList<>();
        l.add(new SamsungTv.AppInfo("11101200001", "Netflix", 2));
        l.add(new SamsungTv.AppInfo("111299001912", "YouTube", 2));
        l.add(new SamsungTv.AppInfo("3201512006785", "Prime Video", 2));
        l.add(new SamsungTv.AppInfo("3201901017640", "Disney+", 2));
        l.add(new SamsungTv.AppInfo("3201606009684", "Spotify", 2));
        l.add(new SamsungTv.AppInfo("org.tizen.browser", "Internet", 4));
        return l;
    }

    // ------------------------------------------------------------------ pagina Numeri

    private View buildNumbersPage() {
        LinearLayout p = column();
        for (int r = 0; r < 3; r++) {
            TextView[] keys = new TextView[3];
            for (int c = 0; c < 3; c++) {
                int n = r * 3 + c + 1;
                keys[c] = keyBtn(String.valueOf(n), "KEY_" + n, KEY, 22, false);
            }
            p.addView(row(62, keys));
        }
        p.addView(row(62,
                keyBtn("LISTA CH", "KEY_CH_LIST", KEY_DARK, 12, false),
                keyBtn("0", "KEY_0", KEY, 22, false),
                keyBtn("CH PREC.", "KEY_PRECH", KEY_DARK, 12, false)));
        p.addView(row(48,
                keyBtn("GUIDA", "KEY_GUIDE", KEY_DARK, 12, false),
                keyBtn("TELEVIDEO", "KEY_TTX_MIX", KEY_DARK, 12, false),
                keyBtn("SOTTOTITOLI", "KEY_SUB_TITLE", KEY_DARK, 12, false)));
        p.addView(row(48,
                keyBtn("FORMATO", "KEY_PICTURE_SIZE", KEY_DARK, 12, false),
                keyBtn("AUDIO DESCR.", "KEY_AD", KEY_DARK, 12, false),
                keyBtn("SLEEP", "KEY_SLEEP", KEY_DARK, 12, false)));
        return p;
    }

    // ------------------------------------------------------------------ azioni

    private void onPowerPressed() {
        String host = prefs.getString("host", null);
        if (host == null) {
            showSettings(true);
            return;
        }
        if (tv.isConnected()) {
            tv.key("KEY_POWER");
            return;
        }
        String mac = prefs.getString("mac", "");
        new Thread(() -> {
            boolean sent = NetUtils.wake(mac, host);
            ui.post(() -> toast(sent ? "Accensione in corso…"
                    : "Per accendere dal telefono serve il MAC del TV: collegati una volta a TV acceso."));
        }).start();
        ui.postDelayed(() -> tv.connect(host), 6000);
    }

    private void showKeyboard(String prefill) {
        if (keyboardDialog != null && keyboardDialog.isShowing()) return;
        LinearLayout box = column();
        box.setPadding(dp(20), dp(8), dp(20), 0);

        TextView hint = new TextView(this);
        hint.setTextSize(13);
        hint.setText(tvKeyboardOpen
                ? "Il TV è pronto: scrivi o detta il testo e premi «Invia»."
                : "Apri prima il campo di ricerca sul TV (es. la lente di YouTube o Netflix). "
                + "Questa finestra si apre da sola quando il TV mostra la sua tastiera.");
        box.addView(hint, matchWrap());

        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        EditText et = new EditText(this);
        et.setHint("Cosa vuoi cercare?");
        et.setSingleLine(true);
        et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        if (prefill != null && !prefill.isEmpty()) {
            et.setText(prefill);
            et.setSelection(prefill.length());
        }
        line.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView mic = button("🎤", ACCENT, 18);
        mic.setOnClickListener(v -> {
            haptic(v);
            startVoice(REQ_VOICE_DICTATE);
        });
        line.addView(mic, squareLp(46));
        LinearLayout.LayoutParams llp = matchWrap();
        llp.topMargin = dp(8);
        box.addView(line, llp);

        TextView modeLbl = new TextView(this);
        modeLbl.setTextSize(13);
        modeLbl.setText("Dove stai scrivendo sul TV?");
        LinearLayout.LayoutParams mlp = matchWrap();
        mlp.topMargin = dp(12);
        box.addView(modeLbl, mlp);

        android.widget.RadioGroup mode = new android.widget.RadioGroup(this);
        String[] modeNames = {"Automatico (riconosce l'app aperta)", "Browser / menu Samsung", "YouTube", "Netflix",
                "Prime Video", "RaiPlay"};
        String[] modeKeys = {"auto", "system", "youtube", "netflix", "prime", "raiplay"};
        String curMode = prefs.getString("kbd_mode2", "auto");
        for (int i = 0; i < modeNames.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(modeNames[i]);
            rb.setId(View.generateViewId());
            rb.setTag(modeKeys[i]);
            mode.addView(rb);
            if (modeKeys[i].equals(curMode)) rb.setChecked(true);
        }
        box.addView(mode, matchWrap());

        CheckBox ytSearch = new CheckBox(this);
        ytSearch.setText("Premi CERCA alla fine (YouTube, RaiPlay)");
        ytSearch.setChecked(prefs.getBoolean("yt_search", true));
        box.addView(ytSearch, matchWrap());

        CheckBox clearFirst = new CheckBox(this);
        clearFirst.setText("Cancella prima il testo già scritto");
        clearFirst.setChecked(prefs.getBoolean("app_clear", false));
        box.addView(clearFirst, matchWrap());

        TextView appHint = new TextView(this);
        appHint.setTextSize(12);
        appHint.setText("YouTube, Netflix, Prime Video e RaiPlay hanno una tastiera loro: l'app la «batte» con le frecce. "
                + "Apri la ricerca sul TV e non muovere il cursore prima di premere Invia. "
                + "In «Automatico» l'app capisce da sola quale app è aperta sul TV.");
        box.addView(appHint, matchWrap());

        Runnable refreshMode = () -> {
            View sel = mode.findViewById(mode.getCheckedRadioButtonId());
            String m = sel != null ? (String) sel.getTag() : "system";
            ytSearch.setVisibility("youtube".equals(m) || "raiplay".equals(m) || "auto".equals(m)
                    ? View.VISIBLE : View.GONE);
            clearFirst.setVisibility("system".equals(m) ? View.GONE : View.VISIBLE);
            appHint.setVisibility("system".equals(m) ? View.GONE : View.VISIBLE);
        };
        mode.setOnCheckedChangeListener((g, id) -> refreshMode.run());
        refreshMode.run();

        Runnable doSend = () -> {
            View sel = mode.findViewById(mode.getCheckedRadioButtonId());
            String m = sel != null ? (String) sel.getTag() : "system";
            prefs.edit().putString("kbd_mode2", m)
                    .putBoolean("yt_search", ytSearch.isChecked())
                    .putBoolean("app_clear", clearFirst.isChecked()).apply();
            String text = et.getText().toString();
            if ("youtube".equals(m)) {
                typeInApp(AppKeyboardTyper.Layout.YOUTUBE, text, clearFirst.isChecked(), ytSearch.isChecked());
            } else if ("netflix".equals(m)) {
                typeInApp(AppKeyboardTyper.Layout.NETFLIX, text, clearFirst.isChecked(), false);
            } else if ("raiplay".equals(m)) {
                typeInApp(AppKeyboardTyper.Layout.RAIPLAY, text, clearFirst.isChecked(), ytSearch.isChecked());
            } else if ("prime".equals(m)) {
                typeInApp(AppKeyboardTyper.Layout.PRIME, text, clearFirst.isChecked(), false);
            } else if ("auto".equals(m)) {
                sendSmart(text);
            } else {
                sendTyped(text);
            }
        };

        keyboardField = et;
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Scrivi sul TV")
                .setView(sv(box))
                .setPositiveButton("Invia", (di, w) -> doSend.run())
                .setNegativeButton("Chiudi", null)
                .create();
        d.setOnDismissListener(di -> {
            if (keyboardDialog == d) {
                keyboardDialog = null;
                keyboardField = null;
            }
        });
        et.setOnEditorActionListener((v, actionId, ev) -> {
            doSend.run();
            d.dismiss();
            return true;
        });
        keyboardDialog = d;
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        d.show();
        et.requestFocus();
    }

    private ScrollView sv(View v) {
        ScrollView s = new ScrollView(this);
        s.addView(v);
        return s;
    }

    /** Batte il testo sulla tastiera a schermo dell'app (YouTube, Netflix) muovendo le frecce. */
    private void typeInApp(AppKeyboardTyper.Layout layout, String text, boolean clearFirst, boolean pressSearch) {
        if (text.trim().isEmpty()) return;
        String appName = layout == AppKeyboardTyper.Layout.RAIPLAY ? "RaiPlay"
                : layout == AppKeyboardTyper.Layout.NETFLIX ? "Netflix"
                : layout == AppKeyboardTyper.Layout.PRIME ? "Prime Video" : "YouTube";
        AppKeyboardTyper.Plan plan = AppKeyboardTyper.plan(layout, text, clearFirst, pressSearch);
        long delay = prefs.getInt("yt_delay", 220);
        int seconds = (int) Math.ceil(plan.keys.size() * delay / 1000.0);

        TextView msg = new TextView(this);
        msg.setPadding(dp(24), dp(12), dp(24), 0);
        msg.setTextSize(14);
        msg.setText("Sto scrivendo «" + text.trim() + "» su " + appName + "…\nCirca " + seconds + " secondi: non toccare il telecomando."
                + (plan.skipped.length() > 0 ? "\n\nSalto i caratteri non presenti sulla tastiera: " + plan.skipped : ""));
        AlertDialog progress = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Digitazione su " + appName)
                .setView(msg)
                .setCancelable(false)
                .setNegativeButton("Interrompi", (di, w) -> tv.cancelSequence())
                .show();
        tv.keySequence(plan.keys, delay, ok -> {
            if (progress.isShowing()) progress.dismiss();
            toast(ok ? "Fatto" : "Digitazione interrotta");
        });
    }

    /**
     * Comando unico: se il TV ha aperto la sua tastiera usa quella; altrimenti chiede al TV quale app
     * è in primo piano e, se è YouTube/Netflix/Prime Video, batte il testo con le frecce.
     */
    private void sendSmart(String text) {
        if (text.trim().isEmpty()) return;
        if (tvKeyboardOpen) {
            sendTyped(text);
            return;
        }
        String host = tv.getHost();
        if (host == null) return;
        boolean clear = prefs.getBoolean("app_clear", false);
        boolean search = prefs.getBoolean("yt_search", true);
        toast("Controllo quale app è aperta sul TV…");
        List<SamsungTv.AppInfo> known = new ArrayList<>(apps);
        new Thread(() -> {
            AppKeyboardTyper.Layout layout = detectForegroundApp(host, known);
            ui.post(() -> {
                if (layout != null) {
                    typeInApp(layout, text, clear, search && (layout == AppKeyboardTyper.Layout.YOUTUBE
                            || layout == AppKeyboardTyper.Layout.RAIPLAY));
                } else {
                    sendTyped(text);
                    toast("App non riconosciuta: ho usato la tastiera di sistema. "
                            + "Se non compare nulla, scegli l'app a mano in «Aa».");
                }
            });
        }).start();
    }

    private static AppKeyboardTyper.Layout detectForegroundApp(String host, List<SamsungTv.AppInfo> known) {
        Map<AppKeyboardTyper.Layout, List<String>> ids = new HashMap<>();
        ids.put(AppKeyboardTyper.Layout.YOUTUBE, new ArrayList<>(java.util.Arrays.asList("111299001912")));
        ids.put(AppKeyboardTyper.Layout.NETFLIX, new ArrayList<>(java.util.Arrays.asList("11101200001", "3201907018807")));
        ids.put(AppKeyboardTyper.Layout.PRIME, new ArrayList<>(java.util.Arrays.asList("3201512006785", "3201910019365")));
        ids.put(AppKeyboardTyper.Layout.RAIPLAY, new ArrayList<>());
        for (SamsungTv.AppInfo a : known) {
            String n = a.name.toLowerCase();
            AppKeyboardTyper.Layout l = n.contains("youtube") && !n.contains("kids") && !n.contains("music")
                    ? AppKeyboardTyper.Layout.YOUTUBE
                    : n.contains("netflix") ? AppKeyboardTyper.Layout.NETFLIX
                    : (n.contains("prime") || n.contains("amazon")) ? AppKeyboardTyper.Layout.PRIME
                    : n.replace(" ", "").contains("raiplay") ? AppKeyboardTyper.Layout.RAIPLAY : null;
            if (l != null && !ids.get(l).contains(a.id)) ids.get(l).add(a.id);
        }
        List<AppKeyboardTyper.Layout> running = new ArrayList<>();
        for (Map.Entry<AppKeyboardTyper.Layout, List<String>> e : ids.entrySet()) {
            for (String id : e.getValue()) {
                int st = SamsungTv.appStatus(host, id);
                if (st == 2) return e.getKey();
                if (st == 1 && !running.contains(e.getKey())) running.add(e.getKey());
            }
        }
        return running.size() == 1 ? running.get(0) : null;
    }

    private void sendTyped(String text) {
        if (text.trim().isEmpty()) return;
        tv.sendText(text);
        toast("Inviato al TV: " + text);
    }

    // ------------------------------------------------------------------ voce

    private void startVoice(int request) {
        if (prefs.getString("host", null) == null) {
            showSettings(true);
            return;
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, request == REQ_VOICE_DICTATE || tvKeyboardOpen
                ? "Detta il testo da cercare"
                : "Di' un comando (es. «alza il volume», «apri YouTube», «canale 5»)");
        try {
            keepConnection = true;
            startActivityForResult(i, request);
        } catch (ActivityNotFoundException e) {
            keepConnection = false;
            toast("Riconoscimento vocale non disponibile: installa o aggiorna l'app Google.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VOICE_COMMAND && requestCode != REQ_VOICE_DICTATE) return;
        if (resultCode != RESULT_OK || data == null) return;
        ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results == null || results.isEmpty()) return;
        String best = results.get(0);

        if (requestCode == REQ_VOICE_DICTATE) {
            if (keyboardField != null) {
                keyboardField.setText(best);
                keyboardField.setSelection(best.length());
            } else {
                sendSmart(best);
            }
            return;
        }

        VoiceCommands.Action a = VoiceCommands.parseBest(results, apps);
        if (a == null && tvKeyboardOpen) {
            // il TV aspetta del testo: la frase detta diventa la ricerca
            sendTyped(best);
            return;
        }
        if (a == null) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Non ho capito il comando")
                    .setMessage("«" + best + "»\n\nSe sul TV è aperto un campo di ricerca posso scriverlo lì.")
                    .setPositiveButton("Scrivi sul TV", (d, w) -> sendSmart(best))
                    .setNeutralButton("Esempi", (d, w) -> showVoiceHelp())
                    .setNegativeButton("Riprova", (d, w) -> startVoice(REQ_VOICE_COMMAND))
                    .show();
            return;
        }
        runVoiceAction(a);
    }

    private void runVoiceAction(VoiceCommands.Action a) {
        switch (a.type) {
            case KEYS:
                if (a.keys.size() == 1) tv.key(a.keys.get(0));
                else tv.keySequence(a.keys, 180);
                break;
            case APP:
                tv.launchApp(a.app);
                break;
            case POWER_OFF:
                if (tv.isConnected()) tv.key("KEY_POWER");
                else {
                    toast("Il TV sembra già spento");
                    return;
                }
                break;
            case POWER_ON:
                if (tv.isConnected()) {
                    toast("Il TV è già acceso");
                    return;
                }
                onPowerPressed();
                return;
            case TEXT:
                sendSmart(a.text);
                return;
            default:
                break;
        }
        toast(a.feedback);
    }

    private void showVoiceHelp() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Esempi di comandi vocali")
                .setMessage("Tocca 🎤 e di':\n\n"
                        + "• «alza il volume» / «abbassa il volume di 5» / «muto»\n"
                        + "• «canale 5» / «canale successivo» / «canale precedente»\n"
                        + "• «apri YouTube» / «apri Netflix» / «Prime Video»\n"
                        + "• «cerca Il Gladiatore» (con la ricerca aperta sul TV)\n"
                        + "• «su», «giù 3 volte», «destra», «ok», «indietro», «home»\n"
                        + "• «pausa», «play», «stop», «avanti veloce»\n"
                        + "• «HDMI 2», «sorgente», «guida», «sottotitoli»\n"
                        + "• «spegni la TV» / «accendi la TV»\n\n"
                        + "Se il TV ha la tastiera aperta, quello che dici viene scritto direttamente nella ricerca.\n"
                        + "Il 🎤 nella finestra «Aa» serve invece solo per dettare testo.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showSettings(boolean autoScan) {
        LinearLayout box = column();
        box.setPadding(dp(20), dp(8), dp(20), 0);

        TextView info = new TextView(this);
        info.setTextSize(13);
        info.setText("Telefono e TV devono essere sulla stessa rete Wi-Fi.\n"
                + "Per accendere il TV dal telefono attiva sul TV: Impostazioni > Generali > Rete > "
                + "Impostazioni esperto > «Accensione con dispositivo mobile».");
        box.addView(info, matchWrap());

        EditText ip = new EditText(this);
        ip.setHint("Indirizzo IP del TV (es. 192.168.1.50)");
        ip.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ip.setText(prefs.getString("host", ""));
        box.addView(ip, matchWrap());

        EditText mac = new EditText(this);
        mac.setHint("MAC Wi-Fi del TV (si compila da solo)");
        mac.setSingleLine(true);
        mac.setText(prefs.getString("mac", ""));
        box.addView(mac, matchWrap());

        TextView scanBtn = button("Cerca il TV sulla rete", ACCENT, 14);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        slp.topMargin = dp(8);
        box.addView(scanBtn, slp);

        TextView scanStatus = new TextView(this);
        scanStatus.setTextSize(12);
        box.addView(scanStatus, matchWrap());

        CheckBox volKeys = new CheckBox(this);
        volKeys.setText("I tasti volume del telefono regolano il TV");
        volKeys.setChecked(prefs.getBoolean("volume_keys", true));
        box.addView(volKeys, matchWrap());

        CheckBox autoKbd = new CheckBox(this);
        autoKbd.setText("Apri la tastiera del telefono quando il TV mostra la sua");
        autoKbd.setChecked(prefs.getBoolean("auto_keyboard", true));
        box.addView(autoKbd, matchWrap());

        TextView speedLbl = new TextView(this);
        speedLbl.setTextSize(13);
        LinearLayout.LayoutParams splp = matchWrap();
        splp.topMargin = dp(8);
        box.addView(speedLbl, splp);
        android.widget.RadioGroup speed = new android.widget.RadioGroup(this);
        speed.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        int[] delays = {350, 220, 150};
        String[] speedNames = {"Lenta", "Normale", "Veloce"};
        int cur = prefs.getInt("yt_delay", 220);
        for (int i = 0; i < delays.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(speedNames[i]);
            rb.setId(View.generateViewId());
            rb.setTag(delays[i]);
            speed.addView(rb);
            if (delays[i] == cur) rb.setChecked(true);
        }
        speedLbl.setText("Velocità digitazione nelle app (se salta lettere, scegli «Lenta»):");
        speed.setOnCheckedChangeListener((g, id) -> {
            View rb = g.findViewById(id);
            if (rb != null) prefs.edit().putInt("yt_delay", (Integer) rb.getTag()).apply();
        });
        box.addView(speed, matchWrap());

        TextView logBtn = button("Registro diagnostico", KEY, 13);
        LinearLayout.LayoutParams lglp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        lglp.topMargin = dp(8);
        box.addView(logBtn, lglp);
        logBtn.setOnClickListener(v -> showLog());

        TextView resetBtn = button("Dimentica autorizzazione e riprova", KEY, 13);
        LinearLayout.LayoutParams rslp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        rslp.topMargin = dp(6);
        box.addView(resetBtn, rslp);
        resetBtn.setOnClickListener(v -> {
            String h = prefs.getString("host", "");
            prefs.edit().remove("token_" + h).remove("port_" + h).apply();
            tv.disconnect();
            if (!h.isEmpty()) tv.connect(h);
            toast("Riprovo da zero: guarda il TV e scegli «Consenti»");
        });

        Runnable scan = () -> {
            scanBtn.setEnabled(false);
            scanStatus.setText("Ricerca in corso…");
            new Thread(() -> {
                List<NetUtils.FoundTv> found = NetUtils.scan((done, total) -> {
                    if (done % 16 == 0) ui.post(() -> scanStatus.setText("Ricerca in corso… " + (done * 100 / total) + "%"));
                });
                ui.post(() -> {
                    scanBtn.setEnabled(true);
                    if (found.isEmpty()) {
                        scanStatus.setText("Nessun TV trovato. Controlla che sia acceso e sulla stessa Wi-Fi, "
                                + "oppure scrivi l'IP a mano.");
                    } else if (found.size() == 1) {
                        applyFound(found.get(0), ip, mac, scanStatus);
                    } else {
                        String[] items = new String[found.size()];
                        for (int i = 0; i < items.length; i++) {
                            NetUtils.FoundTv f = found.get(i);
                            items[i] = f.name + "  (" + f.ip + ")";
                        }
                        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle("Scegli il TV")
                                .setItems(items, (d, which) -> applyFound(found.get(which), ip, mac, scanStatus))
                                .show();
                    }
                });
            }).start();
        };
        scanBtn.setOnClickListener(v -> scan.run());

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Collegamento al TV")
                .setView(sv)
                .setPositiveButton("Salva e collega", (d, w) -> {
                    String host = ip.getText().toString().trim();
                    SharedPreferences.Editor ed = prefs.edit()
                            .putString("mac", mac.getText().toString().trim())
                            .putBoolean("volume_keys", volKeys.isChecked())
                            .putBoolean("auto_keyboard", autoKbd.isChecked());
                    if (!host.isEmpty()) {
                        ed.putString("host", host);
                    }
                    ed.apply();
                    if (!host.isEmpty()) tv.connect(host);
                })
                .setNegativeButton("Chiudi", null)
                .show();
        if (autoScan) scan.run();
    }

    private void showLog() {
        TextView t = new TextView(this);
        t.setText(SamsungTv.getLog());
        t.setTextSize(11);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Registro diagnostico")
                .setView(sv)
                .setPositiveButton("Copia", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("registro", SamsungTv.getLog()));
                    toast("Registro copiato: incollalo nella chat con Claude");
                })
                .setNegativeButton("Chiudi", null)
                .show();
    }

    private void applyFound(NetUtils.FoundTv f, EditText ip, EditText mac, TextView status) {
        ip.setText(f.ip);
        if (!f.mac.isEmpty()) mac.setText(f.mac);
        prefs.edit().putString("name", f.name).apply();
        status.setText("Trovato: " + f.name + (f.model.isEmpty() ? "" : " – " + f.model)
                + "\nPremi «Salva e collega».");
    }

    // ------------------------------------------------------------------ helper grafici

    private TextView keyBtn(String label, String code, int bg, float sp, boolean repeat) {
        TextView b = button(label, bg, sp);
        if (!repeat) {
            b.setOnClickListener(v -> {
                haptic(v);
                tv.key(code);
            });
            return b;
        }
        b.setOnTouchListener(new View.OnTouchListener() {
            private Runnable repeater;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        haptic(v);
                        tv.key(code);
                        repeater = new Runnable() {
                            @Override
                            public void run() {
                                tv.key(code);
                                ui.postDelayed(this, 130);
                            }
                        };
                        ui.postDelayed(repeater, 450);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        if (repeater != null) ui.removeCallbacks(repeater);
                        repeater = null;
                        return true;
                    default:
                        return true;
                }
            }
        });
        return b;
    }

    private TextView button(String label, int bg, float sp) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setTextSize(sp);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(bg, 14));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(MUTED);
        t.setTextSize(11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private ScrollView scroll(View v) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.addView(v);
        return s;
    }

    /** Riga orizzontale di elementi con peso uguale. */
    private LinearLayout row(int heightDp, View... views) {
        LinearLayout r = new LinearLayout(this);
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            r.addView(v, lp);
        }
        r.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp + 8)));
        return r;
    }

    private View single(int heightDp, View v) {
        return row(heightDp, v);
    }

    private LinearLayout.LayoutParams weightLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams squareLp(int sizeDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.leftMargin = dp(8);
        return lp;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private Drawable ripple(int color, int radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), rounded(color, radiusDp),
                rounded(Color.WHITE, radiusDp));
    }

    private void haptic(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
