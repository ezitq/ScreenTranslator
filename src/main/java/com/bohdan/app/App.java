package com.bohdan.app;

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
import java.util.function.Consumer;

public class App {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() ->
                new Screenshot().selectRegion(region -> {
                    // Тут пізніше буде OCR + переклад.
                    // Поки що просто зберігаємо вибрану область для перевірки.
                    System.out.println(new OcrService().detectTextFromImage(region));
                }));
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
     * Після відпускання кнопки викликається onSelected з вирізаним зображенням.
     * Esc скасовує вибір. Метод треба викликати з потоку Swing (EDT).
     */
    public void selectRegion(Consumer<BufferedImage> onSelected) {
        int w = getScreenWidth();
        int h = getScreenHeight();

        // Знімок робимо ДО показу вікна, щоб вікно не потрапило в кадр
        BufferedImage full = screenCapture(0, 0, w, h);

        JFrame window = new JFrame();
        window.setUndecorated(true);
        window.setAlwaysOnTop(true);
        window.setBounds(0, 0, w, h);

        SelectionPanel panel = new SelectionPanel(full, region -> {
            window.dispose();
            if (region != null) {
                onSelected.accept(region);
            }
        });

        window.setContentPane(panel);
        window.setVisible(true);
        panel.requestFocusInWindow();
    }
}

class SelectionPanel extends JPanel {

    private final BufferedImage screenshot;
    private final Consumer<BufferedImage> onDone; // null = скасовано

    private int startX;
    private int endX;
    private int startY;
    private int endY;
    private boolean dragging = false;

    SelectionPanel(BufferedImage screenshot, Consumer<BufferedImage> onDone) {
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

    /**
     * Прямокутник у координатах панелі; працює для будь-якого напрямку перетягування.
     */
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

        onDone.accept(screenshot.getSubimage(x, y, w, h));
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

        // 3) Виділена область: малюємо знімок без затемнення + червона рамка
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

class OcrService {

    public String detectTextFromImage(BufferedImage image){
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath("C:/Users/necha/Documents/ideaproj/in progress/Screen Translator Proj");
        tesseract.setLanguage("eng");
        try {
            return tesseract.doOCR(image);
        } catch (TesseractException e) {
            throw new RuntimeException(e);
        }
    }
}