package com.bohdan.app;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.prefs.Preferences;

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
        menu.add(translate);
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
        ResultWindow window = new ResultWindow(selection.bounds());
        currentResult = window;
        window.setText("Перекладаю…");
        window.display();

        // Фіксуємо мови на момент запуску, щоб зміна в меню не вплинула на поточний переклад
        Lang from = Settings.source();
        Lang to = Settings.target();

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                String text = TextUtils.clean(ocr.detectTextFromImage(selection.image(), from.ocr()));
                if (text.length() < 2) {
                    return null; // нічого не розпізнано
                }
                return translator.translateLong(text, from.code(), to.code());
            }

            @Override
            protected void done() {
                try {
                    String result = get();
                    window.setText(result == null ? "Текст не знайдено" : result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    window.setText("Помилка: " + cause.getMessage());
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

/** Невелике вікно з перекладом біля виділеної області. Закривається Esc або кнопкою ×. */
class ResultWindow {

    private final JFrame frame = new JFrame();
    private final JTextArea area = new JTextArea();

    ResultWindow(Rectangle target) {
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);

        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font("SansSerif", Font.PLAIN, 16));
        area.setBackground(new Color(30, 30, 30));
        area.setForeground(Color.WHITE);
        area.setMargin(new Insets(8, 10, 8, 10));

        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(null);

        JLabel hint = new JLabel("  Esc — закрити");
        hint.setForeground(Color.LIGHT_GRAY);

        JButton close = new JButton("×");
        close.setFocusable(false);
        close.addActionListener(e -> frame.dispose());

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(new Color(50, 50, 50));
        top.add(hint, BorderLayout.WEST);
        top.add(close, BorderLayout.EAST);

        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(top, BorderLayout.NORTH);
        frame.getContentPane().add(scroll, BorderLayout.CENTER);
        frame.getRootPane().setBorder(BorderFactory.createLineBorder(Color.GRAY));

        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        frame.getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                frame.dispose();
            }
        });

        place(target);
    }

    /** Ставить вікно під областю; якщо не вміщується, то над нею. */
    private void place(Rectangle target) {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int w = Math.max(320, Math.min(target.width, 640));
        int h = 180;
        int x = Math.max(0, Math.min(target.x, screen.width - w));
        int y = target.y + target.height + 8;
        if (y + h > screen.height) {
            y = Math.max(0, target.y - h - 8);
        }
        frame.setBounds(x, y, w, h);
    }

    void setText(String text) {
        area.setText(text);
        area.setCaretPosition(0);
    }

    void close() {
        frame.dispose();
    }

    void display() {
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
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
            return json.getAsJsonObject("responseData").get("translatedText").getAsString();
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