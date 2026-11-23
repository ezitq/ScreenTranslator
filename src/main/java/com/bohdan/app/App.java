package com.bohdan.app;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class App {

    // Гаряча клавіша: Ctrl + Shift + E (змініть HOTKEY_CODE та HOTKEY_LABEL, якщо треба)
    private static final int HOTKEY_CODE = NativeKeyEvent.VC_E;
    private static final String HOTKEY_LABEL = "Ctrl+Shift+E";

    // Сервіси створюємо один раз на всю програму
    private static final OcrService OCR = new OcrService();
    private static final TranslationService TRANSLATOR = new TranslationService();

    // Ці поля використовуються лише з потоку Swing
    private static boolean selecting = false;
    private static ResultWindow currentResult;

    public static void main(String[] args) {
        registerHotkey();
        SwingUtilities.invokeLater(App::setupTray);
    }

    /** Запускає вибір області. Ігнорується, якщо вибір уже відкритий. */
    private static void startSelection() {
        if (selecting) {
            return;
        }
        selecting = true;

        // Закриваємо попередній переклад, щоб він не потрапив на новий знімок екрана
        if (currentResult != null) {
            currentResult.close();
            currentResult = null;
        }

        // Невелика пауза, щоб старе вікно встигло зникнути з екрана до знімка
        javax.swing.Timer delay = new javax.swing.Timer(150, e ->
                new Screenshot().selectRegion(selection -> {
                    selecting = false;
                    if (selection != null) {
                        translateInBackground(selection, OCR, TRANSLATOR);
                    }
                }));
        delay.setRepeats(false);
        delay.start();
    }

    /** Реєструє глобальну гарячу клавішу (працює, навіть коли вікно програми не в фокусі). */
    private static void registerHotkey() {
        // JNativeHook за замовчуванням дуже балакучий
        java.util.logging.Logger.getLogger(GlobalScreen.class.getPackage().getName())
                .setLevel(Level.WARNING);

        try {
            GlobalScreen.registerNativeHook();
        } catch (NativeHookException e) {
            throw new RuntimeException("Не вдалося зареєструвати гарячу клавішу", e);
        }

        GlobalScreen.addNativeKeyListener(new NativeKeyListener() {
            @Override
            public void nativeKeyPressed(NativeKeyEvent e) {
                boolean ctrl = (e.getModifiers() & NativeKeyEvent.CTRL_MASK) != 0;
                boolean shift = (e.getModifiers() & NativeKeyEvent.SHIFT_MASK) != 0;
                if (ctrl && shift && e.getKeyCode() == HOTKEY_CODE) {
                    // Хук працює в окремому потоці, а Swing треба викликати з EDT
                    SwingUtilities.invokeLater(App::startSelection);
                }
            }

            @Override
            public void nativeKeyReleased(NativeKeyEvent e) {
            }

            @Override
            public void nativeKeyTyped(NativeKeyEvent e) {
            }
        });
    }

    /** Іконка в треї: подвійний клік або пункт меню запускають переклад, "Вихід" закриває програму. */
    private static void setupTray() {
        if (!SystemTray.isSupported()) {
            System.err.println("Системний трей недоступний. Програма працює, гаряча клавіша: " + HOTKEY_LABEL);
            return;
        }

        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(40, 110, 220));
        g.fillOval(0, 0, 15, 15);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        g.drawString("T", 4, 12);
        g.dispose();

        PopupMenu menu = new PopupMenu();
        MenuItem translate = new MenuItem("Перекласти (" + HOTKEY_LABEL + ")");
        translate.addActionListener(e -> startSelection());
        MenuItem exit = new MenuItem("Вихід");
        exit.addActionListener(e -> shutdown());
        MenuItem dictionary = new MenuItem("Словник…");
        dictionary.addActionListener(e -> DictionaryWindow.open());
        menu.add(translate);
        menu.add(dictionary);
        menu.addSeparator();
        menu.add(languageMenu("Мова оригіналу", Settings.source(), Settings::setSource));
        menu.add(languageMenu("Перекласти на", Settings.target(), Settings::setTarget));
        menu.addSeparator();
        menu.add(exit);

        TrayIcon trayIcon = new TrayIcon(icon, "Screen Translator", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> startSelection()); // подвійний клік

        try {
            SystemTray.getSystemTray().add(trayIcon);
            trayIcon.displayMessage("Screen Translator", "Працює у фоні. Натисніть " + HOTKEY_LABEL,
                    TrayIcon.MessageType.INFO);
        } catch (AWTException e) {
            System.err.println("Не вдалося додати іконку в трей: " + e.getMessage());
        }
    }

    /** Підменю з мовами: біля вибраної стоїть галочка. */
    private static Menu languageMenu(String title, Lang current, Consumer<Lang> onPick) {
        Menu menu = new Menu(title);
        List<CheckboxMenuItem> items = new ArrayList<>();

        for (Lang lang : Lang.ALL) {
            CheckboxMenuItem item = new CheckboxMenuItem(lang.name(), lang.equals(current));
            item.addItemListener(e -> {
                onPick.accept(lang);
                for (int i = 0; i < items.size(); i++) {
                    items.get(i).setState(Lang.ALL.get(i).equals(lang));
                }
            });
            items.add(item);
            menu.add(item);
        }
        return menu;
    }

    private static void shutdown() {
        try {
            GlobalScreen.unregisterNativeHook();
        } catch (NativeHookException ignored) {
        }
        System.exit(0);
    }

    /**
     * Показує вікно "Перекладаю…", а OCR і переклад виконує у фоновому потоці,
     * щоб інтерфейс не зависав. Результат повертається в потік Swing.
     */
    private static void translateInBackground(Selection selection, OcrService ocr, TranslationService translator) {
        ResultWindow window = new ResultWindow(selection.bounds(), translator);
        currentResult = window;
        window.showStatus("Перекладаю…");
        window.display();

        // Фіксуємо мови на момент запуску, щоб зміна в меню не вплинула на поточний переклад
        Lang from = Settings.source();
        Lang to = Settings.target();

        new SwingWorker<TranslationResult, Void>() {
            @Override
            protected TranslationResult doInBackground() {
                String text = TextUtils.clean(ocr.detectTextFromImage(selection.image(), from.ocr()));
                if (text.length() < 2) {
                    return null; // нічого не розпізнано
                }
                return new TranslationResult(text, translator.translateLong(text, from.code(), to.code()));
            }

            @Override
            protected void done() {
                try {
                    TranslationResult result = get();
                    if (result == null) {
                        window.showStatus("Текст не знайдено");
                    } else {
                        window.showResult(result.original(), result.translation(), from, to);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    window.showStatus("Помилка: " + cause.getMessage());
                }
            }
        }.execute();
    }
}

/** Вибрана область: зображення + її положення на екрані. */
record Selection(BufferedImage image, Rectangle bounds) {
}

/**
 * Мова: code для MyMemory, ocr для Tesseract (потрібен файл <ocr>.traineddata у tessdata).
 */
record Lang(String code, String name, String ocr) {

    static final List<Lang> ALL = List.of(
            new Lang("en", "English", "eng"),
            new Lang("uk", "Українська", "ukr"),
            new Lang("pl", "Polski", "pol"),
            new Lang("de", "Deutsch", "deu"),
            new Lang("fr", "Français", "fra")
    );

    static Lang byCode(String code) {
        return ALL.stream().filter(l -> l.code().equals(code)).findFirst().orElse(ALL.get(0));
    }
}

/** Вибрані мови зберігаються між запусками (java.util.prefs). */
class Settings {

    private static final Preferences PREFS = Preferences.userNodeForPackage(Settings.class);

    private static volatile Lang source = Lang.byCode(PREFS.get("source", "en"));
    private static volatile Lang target = Lang.byCode(PREFS.get("target", "uk"));

    static Lang source() {
        return source;
    }

    static Lang target() {
        return target;
    }

    static void setSource(Lang lang) {
        source = lang;
        PREFS.put("source", lang.code());
    }

    static void setTarget(Lang lang) {
        target = lang;
        PREFS.put("target", lang.code());
    }
}

class TextUtils {

    /** Прибирає переноси рядків з OCR, щоб речення не ламалися при перекладі. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("(?<=\\p{L})-\\s*\\n\\s*", "") // "inter-\nnational" -> "international"
                .replaceAll("\\s*\\n\\s*", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }
}

class Screenshot {

    private static final Logger log = LoggerFactory.getLogger(Screenshot.class);

    public String getScreenSize() {
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        return screenSize.width + " x " + screenSize.height;
    }

    public static int getScreenWidth() {
        return Toolkit.getDefaultToolkit().getScreenSize().width;
    }

    public static int getScreenHeight() {
        return Toolkit.getDefaultToolkit().getScreenSize().height;
    }

    private Rectangle createRectangle(int x, int y, int width, int height) {
        return new Rectangle(x, y, width, height);
    }

    private BufferedImage screenCapture(int x, int y, int width, int height) {
        Rectangle rect = createRectangle(x, y, width, height);
        try {
            return new Robot().createScreenCapture(rect);
        } catch (AWTException e) {
            throw new RuntimeException(e);
        }
    }

    public void savePicture(BufferedImage image, String fileName) {
        try {
            ImageIO.write(image, "png", new File(fileName));
            log.info("Saved {} ({}x{})", fileName, image.getWidth(), image.getHeight());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void takeScreenshot(int x, int y, int width, int height) {
        savePicture(screenCapture(x, y, width, height), "test.png");
    }

    /**
     * Показує "заморожений" екран на весь розмір. Користувач виділяє область мишею.
     * Після відпускання кнопки викликається onSelected з вибраною областю.
     * Esc скасовує вибір: тоді onSelected викликається з null.
     * Метод треба викликати з потоку Swing (EDT).
     */
    public void selectRegion(Consumer<Selection> onSelected) {
        int w = getScreenWidth();
        int h = getScreenHeight();

        // Знімок робимо ДО показу вікна, щоб вікно не потрапило в кадр
        BufferedImage full = screenCapture(0, 0, w, h);

        JFrame window = new JFrame();
        window.setUndecorated(true);
        window.setAlwaysOnTop(true);
        window.setBounds(0, 0, w, h);

        SelectionPanel panel = new SelectionPanel(full, selection -> {
            window.dispose();
            onSelected.accept(selection); // null = скасовано
        });

        window.setContentPane(panel);
        window.setVisible(true);
        window.toFront();
        window.requestFocus();
        panel.requestFocusInWindow();
    }
}

class SelectionPanel extends JPanel {

    private final BufferedImage screenshot;
    private final Consumer<Selection> onDone; // null = скасовано

    private int startX;
    private int endX;
    private int startY;
    private int endY;
    private boolean dragging = false;

    SelectionPanel(BufferedImage screenshot, Consumer<Selection> onDone) {
        this.screenshot = screenshot;
        this.onDone = onDone;

        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        setFocusable(true);

        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                startX = e.getX();
                startY = e.getY();
                endX = e.getX();
                endY = e.getY();
                dragging = true;
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                endX = e.getX();
                endY = e.getY();
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                endX = e.getX();
                endY = e.getY();
                dragging = false;
                finishSelection();
            }
        };
        addMouseListener(adapter);        // press / release
        addMouseMotionListener(adapter);  // drag

        // Esc = скасувати
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "cancel");
        getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                onDone.accept(null);
            }
        });
    }

    /** Прямокутник у координатах панелі; працює для будь-якого напрямку перетягування. */
    private Rectangle selection() {
        int x = Math.min(startX, endX);
        int y = Math.min(startY, endY);
        int w = Math.abs(endX - startX);
        int h = Math.abs(endY - startY);
        return new Rectangle(x, y, w, h);
    }

    private void finishSelection() {
        Rectangle r = selection();

        // Занадто маленька область = випадковий клік, ігноруємо
        if (r.width < 5 || r.height < 5) {
            repaint();
            return;
        }

        // Перерахунок координат панелі в координати знімка (на випадок масштабування екрана)
        double scaleX = screenshot.getWidth() / (double) getWidth();
        double scaleY = screenshot.getHeight() / (double) getHeight();

        int x = Math.max(0, Math.min((int) (r.x * scaleX), screenshot.getWidth() - 1));
        int y = Math.max(0, Math.min((int) (r.y * scaleY), screenshot.getHeight() - 1));
        int w = Math.min((int) (r.width * scaleX), screenshot.getWidth() - x);
        int h = Math.min((int) (r.height * scaleY), screenshot.getHeight() - y);

        // Вікно виділення розташоване в (0,0), тому координати панелі = координати екрана
        onDone.accept(new Selection(screenshot.getSubimage(x, y, w, h), r));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();

        // 1) Знімок на весь розмір
        g2.drawImage(screenshot, 0, 0, getWidth(), getHeight(), null);

        // 2) Затемнення поверх
        g2.setColor(new Color(0, 0, 0, 110));
        g2.fillRect(0, 0, getWidth(), getHeight());

        // 3) Виділена область: знімок без затемнення + червона рамка
        if (dragging) {
            Rectangle r = selection();
            g2.setClip(r);
            g2.drawImage(screenshot, 0, 0, getWidth(), getHeight(), null);
            g2.setClip(null);

            g2.setColor(Color.RED);
            g2.setStroke(new BasicStroke(2f));
            g2.drawRect(r.x, r.y, r.width, r.height);
        }

        g2.dispose();
    }
}

/** Результат OCR + перекладу. */
record TranslationResult(String original, String translation) {
}

/**
 * Вікно біля виділеної області: зверху оригінал (клікабельний), знизу переклад.
 * Клік по слову або виділення фрази відкриває попап для додавання у словник.
 */
class ResultWindow {

    private static final Pattern WORD = Pattern.compile("[\\p{L}'’-]+");
    private static final Highlighter.HighlightPainter SAVED =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(46, 125, 50));

    private final JFrame frame = new JFrame();
    private final JTextPane originalPane = new JTextPane();
    private final JTextArea translationArea = new JTextArea();
    private final TranslationService translator;

    private String original = "";
    private Lang from;
    private Lang to;
    private WordDialog activeDialog;

    ResultWindow(Rectangle target, TranslationService translator) {
        this.translator = translator;
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);

        Font font = new Font("SansSerif", Font.PLAIN, 16);

        originalPane.setEditable(false);
        originalPane.setFont(font);
        originalPane.setBackground(new Color(30, 30, 30));
        originalPane.setForeground(new Color(215, 215, 215));
        originalPane.setSelectionColor(new Color(60, 100, 170));
        originalPane.setSelectedTextColor(Color.WHITE);
        originalPane.setMargin(new Insets(8, 10, 8, 10));
        originalPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    onOriginalClicked(e);
                }
            }
        });

        translationArea.setEditable(false);
        translationArea.setLineWrap(true);
        translationArea.setWrapStyleWord(true);
        translationArea.setFont(font);
        translationArea.setBackground(new Color(38, 38, 50));
        translationArea.setForeground(Color.WHITE);
        translationArea.setMargin(new Insets(8, 10, 8, 10));

        JScrollPane originalScroll = new JScrollPane(originalPane);
        originalScroll.setBorder(null);
        JScrollPane translationScroll = new JScrollPane(translationArea);
        translationScroll.setBorder(null);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, originalScroll, translationScroll);
        split.setResizeWeight(0.5);
        split.setDividerSize(4);
        split.setBorder(null);

        JLabel hint = new JLabel("  Клік по слову або виділення фрази — у словник  ·  Esc — закрити");
        hint.setForeground(Color.LIGHT_GRAY);
        hint.setFont(hint.getFont().deriveFont(11f));

        JButton close = new JButton("×");
        close.setFocusable(false);
        close.addActionListener(e -> close());

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(new Color(50, 50, 50));
        top.add(hint, BorderLayout.WEST);
        top.add(close, BorderLayout.EAST);

        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(top, BorderLayout.NORTH);
        frame.getContentPane().add(split, BorderLayout.CENTER);
        frame.getRootPane().setBorder(BorderFactory.createLineBorder(Color.GRAY));

        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        frame.getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                close();
            }
        });

        place(target);
    }

    /** Ставить вікно під областю; якщо не вміщується, то над нею. */
    private void place(Rectangle target) {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int w = Math.max(380, Math.min(target.width, 700));
        int h = 300;
        int x = Math.max(0, Math.min(target.x, screen.width - w));
        int y = target.y + target.height + 8;
        if (y + h > screen.height) {
            y = Math.max(0, target.y - h - 8);
        }
        frame.setBounds(x, y, w, h);
    }

    /** Повідомлення на кшталт "Перекладаю…" або помилка. */
    void showStatus(String message) {
        original = "";
        from = null;
        originalPane.setText("");
        translationArea.setText(message);
        translationArea.setCaretPosition(0);
    }

    void showResult(String original, String translation, Lang from, Lang to) {
        this.original = original;
        this.from = from;
        this.to = to;
        originalPane.setText(original);
        originalPane.setCaretPosition(0);
        translationArea.setText(translation);
        translationArea.setCaretPosition(0);
        refreshHighlights();
    }

    /** Підсвічує зеленим слова, які вже є у словнику. */
    private void refreshHighlights() {
        Highlighter highlighter = originalPane.getHighlighter();
        highlighter.removeAllHighlights();
        if (from == null) {
            return;
        }
        Matcher m = WORD.matcher(original);
        while (m.find()) {
            String word = trimEdges(m.group());
            if (!word.isEmpty() && WordStore.contains(word, from.code(), to.code())) {
                try {
                    highlighter.addHighlight(m.start(), m.end(), SAVED);
                } catch (BadLocationException ignored) {
                }
            }
        }
    }

    // ---------- клік по слову / виділення фрази ----------

    private void onOriginalClicked(MouseEvent e) {
        if (from == null) {
            return; // результату ще немає
        }
        try {
            Document doc = originalPane.getDocument();
            String text = doc.getText(0, doc.getLength());
            int start = originalPane.getSelectionStart();
            int end = originalPane.getSelectionEnd();

            if (start == end) {
                // Просто клік: шукаємо межі слова навколо позиції курсора
                int pos = originalPane.viewToModel2D(e.getPoint());
                if (pos < 0) {
                    return;
                }
                start = pos;
                end = pos;
                while (start > 0 && isWordChar(text.charAt(start - 1))) {
                    start--;
                }
                while (end < text.length() && isWordChar(text.charAt(end))) {
                    end++;
                }
            }
            if (start >= end) {
                return;
            }

            String phrase = trimEdges(text.substring(start, end)).replaceAll("\\s+", " ");
            if (phrase.length() < 2) {
                return;
            }
            openDialog(phrase, sentenceAround(text, start, end), e.getLocationOnScreen());
        } catch (BadLocationException ignored) {
        }
    }

    private void openDialog(String phrase, String context, Point screenPoint) {
        if (activeDialog != null) {
            activeDialog.close();
        }
        activeDialog = new WordDialog(frame, phrase, context, from, to, translator, this::refreshHighlights);
        activeDialog.show(screenPoint);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetter(c) || c == '\'' || c == '’' || c == '-';
    }

    /** Прибирає з країв усе, що не літера (апострофи, дефіси, розділові знаки). */
    private static String trimEdges(String s) {
        return s.replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "");
    }

    /** Речення, у якому знаходиться слово: це корисний контекст для запам'ятовування. */
    private static String sentenceAround(String text, int start, int end) {
        int s = start;
        while (s > 0 && ".!?".indexOf(text.charAt(s - 1)) < 0) {
            s--;
        }
        int e = end;
        while (e < text.length() && ".!?".indexOf(text.charAt(e)) < 0) {
            e++;
        }
        if (e < text.length()) {
            e++; // включаємо крапку
        }
        return text.substring(s, e).trim();
    }

    void close() {
        if (activeDialog != null) {
            activeDialog.close();
        }
        frame.dispose();
    }

    void display() {
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
    }
}

/** Попап "додати слово до словника". */
class WordDialog {

    private static final String LOADING = "…";

    private final JDialog dialog;
    private final JTextField wordField;
    private final JTextField translationField = new JTextField(22);
    private final JLabel status = new JLabel(" ");

    private final String context;
    private final Lang from;
    private final Lang to;
    private final TranslationService translator;
    private final Runnable onAdded;

    WordDialog(Window owner, String phrase, String context, Lang from, Lang to,
               TranslationService translator, Runnable onAdded) {
        this.context = context;
        this.from = from;
        this.to = to;
        this.translator = translator;
        this.onAdded = onAdded;

        dialog = new JDialog(owner);
        dialog.setUndecorated(true);
        dialog.setAlwaysOnTop(true);

        wordField = new JTextField(phrase, 22);

        JTextArea contextArea = new JTextArea(context, 3, 22);
        contextArea.setEditable(false);
        contextArea.setLineWrap(true);
        contextArea.setWrapStyleWord(true);
        contextArea.setBackground(new Color(245, 245, 245));

        JButton retry = new JButton("↻");
        retry.setToolTipText("Перекласти ще раз");
        retry.setFocusable(false);
        retry.addActionListener(e -> loadTranslation());

        JButton add = new JButton("Додати");
        add.addActionListener(e -> addWord());
        JButton cancel = new JButton("Скасувати");
        cancel.addActionListener(e -> close());

        JPanel translationRow = new JPanel(new BorderLayout(4, 0));
        translationRow.add(translationField, BorderLayout.CENTER);
        translationRow.add(retry, BorderLayout.EAST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(cancel);
        buttons.add(add);

        status.setForeground(new Color(180, 40, 40));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        int row = 0;
        addRow(form, new JLabel("Слово / фраза (" + from.name() + ")"), row++, 0);
        addRow(form, wordField, row++, 6);
        addRow(form, new JLabel("Переклад (" + to.name() + ")"), row++, 0);
        addRow(form, translationRow, row++, 6);
        addRow(form, new JLabel("Контекст"), row++, 0);
        addRow(form, new JScrollPane(contextArea), row++, 6);
        addRow(form, status, row++, 4);
        addRow(form, buttons, row, 0);

        dialog.setContentPane(form);
        dialog.getRootPane().setDefaultButton(add); // Enter = Додати
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        dialog.getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                close();
            }
        });
        dialog.pack();
    }

    private static void addRow(JPanel panel, Component c, int row, int bottomGap) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(0, 0, bottomGap, 0);
        panel.add(c, g);
    }

    void show(Point screenPoint) {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int x = Math.max(0, Math.min(screenPoint.x, screen.width - dialog.getWidth()));
        int y = Math.max(0, Math.min(screenPoint.y + 20, screen.height - dialog.getHeight()));
        dialog.setLocation(x, y);
        dialog.setVisible(true);
        wordField.requestFocusInWindow();
        wordField.selectAll();
        loadTranslation();
    }

    /** Переклад слова у фоні (результати кешуються в TranslationService). */
    private void loadTranslation() {
        String word = wordField.getText().trim();
        if (word.isEmpty()) {
            return;
        }
        status.setText(" ");
        translationField.setText(LOADING);

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return translator.translate(word, from.code(), to.code());
            }

            @Override
            protected void done() {
                try {
                    // Не затираємо, якщо користувач уже встиг щось ввести
                    if (LOADING.equals(translationField.getText())) {
                        translationField.setText(get());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    translationField.setText("");
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    status.setText("Помилка перекладу: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void addWord() {
        String word = wordField.getText().trim();
        String translation = translationField.getText().trim();
        if (LOADING.equals(translation)) {
            translation = "";
        }
        if (word.isEmpty()) {
            return;
        }
        WordEntry entry = new WordEntry(word, translation, context,
                from.code(), to.code(), LocalDate.now().toString());
        if (WordStore.add(entry)) {
            onAdded.run();
            close();
        } else {
            status.setText("Це слово вже є у словнику");
        }
    }

    void close() {
        dialog.dispose();
    }
}

/** Запис словника. */
record WordEntry(String word, String translation, String context, String from, String to, String added) {
}

/** Словник на диску: ~/.screen-translator/words.json. Усі методи потокобезпечні. */
class WordStore {

    private static final Path FILE = Path.of(System.getProperty("user.home"), ".screen-translator", "words.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<WordEntry> WORDS = new ArrayList<>();
    private static boolean loaded = false;

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(FILE)) {
            return;
        }
        try {
            String json = Files.readString(FILE, StandardCharsets.UTF_8);
            List<WordEntry> list = GSON.fromJson(json, new TypeToken<List<WordEntry>>() {
            }.getType());
            if (list != null) {
                WORDS.addAll(list);
            }
        } catch (IOException | JsonParseException e) {
            // Пошкоджений файл не затираємо: робимо копію, щоб нічого не втратити
            try {
                Files.copy(FILE, FILE.resolveSibling("words.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            System.err.println("Не вдалося прочитати словник: " + e.getMessage());
        }
    }

    private static boolean same(WordEntry e, String word, String from, String to) {
        return e.word().equalsIgnoreCase(word) && e.from().equals(from) && e.to().equals(to);
    }

    static synchronized boolean contains(String word, String from, String to) {
        ensureLoaded();
        return WORDS.stream().anyMatch(e -> same(e, word, from, to));
    }

    /** Повертає false, якщо таке слово (для цієї пари мов) уже є. */
    static synchronized boolean add(WordEntry entry) {
        ensureLoaded();
        if (contains(entry.word(), entry.from(), entry.to())) {
            return false;
        }
        WORDS.add(entry);
        save();
        return true;
    }

    static synchronized void removeAll(List<WordEntry> entries) {
        ensureLoaded();
        WORDS.removeAll(entries);
        save();
    }

    static synchronized List<WordEntry> all() {
        ensureLoaded();
        return new ArrayList<>(WORDS);
    }

    static synchronized void exportCsv(Path file) throws IOException {
        ensureLoaded();
        List<String> lines = new ArrayList<>();
        lines.add("word,translation,context,from,to,added");
        for (WordEntry e : WORDS) {
            lines.add(String.join(",",
                    csv(e.word()), csv(e.translation()), csv(e.context()),
                    csv(e.from()), csv(e.to()), csv(e.added())));
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static String csv(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling("words.json.tmp");
            Files.writeString(tmp, GSON.toJson(WORDS), StandardCharsets.UTF_8);
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Не вдалося зберегти словник: " + e.getMessage());
        }
    }
}

/** Вікно зі списком збережених слів (пункт "Словник…" у меню трея). */
class DictionaryWindow {

    private static DictionaryWindow instance;

    private final JFrame frame = new JFrame("Словник");
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Слово", "Переклад", "Контекст", "Мови", "Додано"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private List<WordEntry> rows = new ArrayList<>();

    /** Відкриває вікно, а якщо воно вже відкрите, то оновлює й виносить наперед. */
    static void open() {
        if (instance != null) {
            instance.reload();
            instance.frame.toFront();
            return;
        }
        instance = new DictionaryWindow();
    }

    private DictionaryWindow() {
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(130);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.getColumnModel().getColumn(2).setPreferredWidth(380);
        table.getColumnModel().getColumn(3).setPreferredWidth(70);
        table.getColumnModel().getColumn(4).setPreferredWidth(90);

        JButton delete = new JButton("Видалити виділене");
        delete.addActionListener(e -> deleteSelected());
        JButton export = new JButton("Експорт у CSV…");
        export.addActionListener(e -> exportCsv());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(delete);
        buttons.add(export);

        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                instance = null;
            }
        });
        frame.getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        frame.getContentPane().add(buttons, BorderLayout.SOUTH);
        frame.setSize(860, 460);
        frame.setLocationRelativeTo(null);

        reload();
        frame.setVisible(true);
    }

    private void reload() {
        rows = WordStore.all();
        model.setRowCount(0);
        for (WordEntry w : rows) {
            model.addRow(new Object[]{
                    w.word(), w.translation(), w.context(),
                    w.from().toUpperCase() + " → " + w.to().toUpperCase(), w.added()
            });
        }
        frame.setTitle("Словник (" + rows.size() + ")");
    }

    private void deleteSelected() {
        int[] selected = table.getSelectedRows();
        if (selected.length == 0) {
            return;
        }
        List<WordEntry> toRemove = new ArrayList<>();
        for (int viewRow : selected) {
            toRemove.add(rows.get(table.convertRowIndexToModel(viewRow)));
        }
        WordStore.removeAll(toRemove);
        reload();
    }

    private void exportCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("words.csv"));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            WordStore.exportCsv(chooser.getSelectedFile().toPath());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Не вдалося зберегти файл: " + e.getMessage(),
                    "Помилка", JOptionPane.ERROR_MESSAGE);
        }
    }
}

class OcrService {

    // Шлях можна задати змінною середовища TESSDATA_PATH, інакше береться цей
    private static final String TESSDATA = System.getenv().getOrDefault(
            "TESSDATA_PATH", "C:/Users/necha/Documents/ideaproj/in progress/Screen Translator Proj");

    private final Tesseract tesseract = new Tesseract();

    OcrService() {
        tesseract.setDatapath(TESSDATA);
    }

    // Tesseract не потокобезпечний, тому synchronized
    public synchronized String detectTextFromImage(BufferedImage image, String ocrLang) {
        try {
            tesseract.setLanguage(ocrLang);
            return tesseract.doOCR(prepare(image));
        } catch (TesseractException e) {
            throw new RuntimeException("OCR не вдався. Перевірте, що " + ocrLang
                    + ".traineddata лежить у " + TESSDATA + " (" + e.getMessage() + ")", e);
        }
    }

    /**
     * Покращує зображення перед OCR: збільшує (Tesseract краще читає великі літери)
     * і інвертує, якщо тло темне (світлий текст на темному).
     */
    private static BufferedImage prepare(BufferedImage src) {
        int scale = Math.max(1, Math.min(src.getHeight() < 200 ? 3 : 2, 3000 / src.getWidth()));

        BufferedImage out = new BufferedImage(
                src.getWidth() * scale, src.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, out.getWidth(), out.getHeight(), null);
        g.dispose();

        // Середня яскравість (беремо кожен 4-й піксель, цього достатньо)
        long sum = 0;
        long count = 0;
        for (int y = 0; y < out.getHeight(); y += 4) {
            for (int x = 0; x < out.getWidth(); x += 4) {
                int rgb = out.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int gr = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                sum += (r * 299 + gr * 587 + b * 114) / 1000;
                count++;
            }
        }

        if (count > 0 && sum / count < 128) {
            for (int y = 0; y < out.getHeight(); y++) {
                for (int x = 0; x < out.getWidth(); x++) {
                    out.setRGB(x, y, out.getRGB(x, y) ^ 0x00FFFFFF);
                }
            }
        }
        return out;
    }
}

class TranslationService {

    private static final int MAX_BYTES = 450; // MyMemory приймає ~500 байтів на запит

    // Кеш, щоб повторні запити (наприклад, клік по тому самому слову) не витрачали ліміт
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Розбиває довгий текст на частини за реченнями, перекладає кожну й склеює. */
    public String translateLong(String text, String from, String to) {
        List<String> translated = new ArrayList<>();
        for (String chunk : splitIntoChunks(text, MAX_BYTES)) {
            translated.add(translate(chunk, from, to));
        }
        return String.join(" ", translated);
    }

    public String translate(String text, String from, String to) {
        String cacheKey = from + "|" + to + "|" + text;
        String cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String q = URLEncoder.encode(text, StandardCharsets.UTF_8);
        String pair = URLEncoder.encode(from + "|" + to, StandardCharsets.UTF_8);
        String url = "https://api.mymemory.translated.net/get?q=%s&langpair=%s".formatted(q, pair);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            int status = json.get("responseStatus").getAsInt();
            if (status != 200) {
                throw new RuntimeException("MyMemory " + status + ": " + json);
            }
            String result = json.getAsJsonObject("responseData").get("translatedText").getAsString();
            cache.put(cacheKey, result);
            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ---------- розбиття тексту на частини ----------

    private static int bytes(CharSequence s) {
        return s.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    static List<String> splitIntoChunks(String text, int maxBytes) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String sentence : text.split("(?<=[.!?])\\s+")) {
            for (String piece : splitLong(sentence, maxBytes)) {
                if (current.length() > 0 && bytes(current) + 1 + bytes(piece) > maxBytes) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(piece);
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /** Якщо одне речення довше за ліміт, ріжемо його по словах. */
    private static List<String> splitLong(String sentence, int maxBytes) {
        if (bytes(sentence) <= maxBytes) {
            return List.of(sentence);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : sentence.split(" ")) {
            if (current.length() > 0 && bytes(current) + 1 + bytes(word) > maxBytes) {
                parts.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }
}