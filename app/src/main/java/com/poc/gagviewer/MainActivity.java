package com.poc.gagviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Picture;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.OutputStream;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int REQUEST_OPEN = 1;
    private static final int REQUEST_SAVE_SVG = 2;
    private static final int REQUEST_SAVE_PNG = 3;
    private static final int REQUEST_SAVE_PDF = 4;

    private WebView webView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    // Último diagrama real cargado (no los SVG de estado/aviso).
    private String currentSvg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Necesario ANTES de crear el WebView para poder capturar el
        // documento completo (no solo lo visible) al exportar PNG/PDF.
        WebView.enableSlowWholeDocumentDraw();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);

        Button openButton = new Button(this);
        openButton.setText("Abrir .gag / .sdjf / .svg");
        openButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPicker();
            }
        });
        buttonRow.addView(openButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));

        Button exportButton = new Button(this);
        exportButton.setText("Exportar…");
        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openExportDialog();
            }
        });
        buttonRow.addView(exportButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(buttonRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        renderSvg(messageSvg("GAG Viewer", "Toca “Abrir” y elige un .gag, .sdjf o .svg"));
        setContentView(root);

        // Arrancar Python en segundo plano para no bloquear el primer frame.
        new Thread(new Runnable() {
            @Override
            public void run() {
                ensurePython();
            }
        }).start();

        handleViewIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleViewIntent(intent);
    }

    private void handleViewIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }
        Uri uri = intent.getData();
        if (uri != null) {
            loadFrom(uri);
        }
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_OPEN);
        } catch (Exception e) {
            Toast.makeText(this, "No hay app de archivos disponible", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_OPEN) {
            loadFrom(data.getData());
        } else if (requestCode == REQUEST_SAVE_SVG || requestCode == REQUEST_SAVE_PNG
                || requestCode == REQUEST_SAVE_PDF) {
            exportTo(data.getData(), requestCode);
        }
    }

    // Lee el archivo y decide cómo mostrarlo según su contenido.
    private void loadFrom(Uri uri) {
        final String text = readText(uri);
        if (text == null) {
            return;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("<")) {
            // Ya es SVG: renderizar directo.
            currentSvg = text;
            renderSvg(text);
        } else if (trimmed.startsWith("{")) {
            // Es .gag/.sdjf (JSON): convertir con el motor AlmaGag en Python.
            renderGagAsync(text);
        } else {
            Toast.makeText(this, "Formato no reconocido (esperaba SVG o JSON .gag/.sdjf)",
                    Toast.LENGTH_LONG).show();
        }
    }

    // Convierte el .gag/.sdjf a SVG con el motor real, fuera del hilo de UI.
    private void renderGagAsync(final String gagText) {
        renderSvg(messageSvg("Renderizando…", "Ejecutando el motor AlmaGag"));
        new Thread(new Runnable() {
            @Override
            public void run() {
                String svg;
                try {
                    ensurePython();
                    Python py = Python.getInstance();
                    PyObject module = py.getModule("gagrender");
                    PyObject result = module.callAttr(
                            "render", gagText, getFilesDir().getAbsolutePath());
                    svg = result.toString();
                } catch (Throwable t) {
                    svg = messageSvg("Fallo al iniciar el motor", String.valueOf(t));
                }
                final String finalSvg = svg;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        currentSvg = finalSvg;
                        renderSvg(finalSvg);
                    }
                });
            }
        }).start();
    }

    // ---- Exportación SVG / PNG / PDF ----

    private void openExportDialog() {
        if (currentSvg == null) {
            Toast.makeText(this, "Abre primero un diagrama", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] options = {"SVG (vector original)", "PNG (imagen)", "PDF (documento)"};
        new AlertDialog.Builder(this)
                .setTitle("Exportar como")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        createDocument("image/svg+xml", "diagrama.svg", REQUEST_SAVE_SVG);
                    } else if (which == 1) {
                        createDocument("image/png", "diagrama.png", REQUEST_SAVE_PNG);
                    } else {
                        createDocument("application/pdf", "diagrama.pdf", REQUEST_SAVE_PDF);
                    }
                })
                .show();
    }

    private void createDocument(String mime, String name, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir el guardador de archivos", Toast.LENGTH_LONG).show();
        }
    }

    private void exportTo(Uri uri, int requestCode) {
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (requestCode == REQUEST_SAVE_SVG) {
                out.write(currentSvg.getBytes(StandardCharsets.UTF_8));
            } else {
                // Captura del documento completo tal como está renderizado.
                Picture picture = webView.capturePicture();
                if (picture == null || picture.getWidth() <= 0 || picture.getHeight() <= 0) {
                    Toast.makeText(this, "Nada que exportar todavía", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (requestCode == REQUEST_SAVE_PNG) {
                    Bitmap bmp = Bitmap.createBitmap(
                            picture.getWidth(), picture.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bmp);
                    canvas.drawColor(Color.WHITE);
                    picture.draw(canvas);
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                    bmp.recycle();
                } else {
                    // El Picture se re-dibuja en el PDF: conserva vectores.
                    PdfDocument doc = new PdfDocument();
                    PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                            picture.getWidth(), picture.getHeight(), 1).create();
                    PdfDocument.Page page = doc.startPage(info);
                    page.getCanvas().drawColor(Color.WHITE);
                    picture.draw(page.getCanvas());
                    doc.finishPage(page);
                    doc.writeTo(out);
                    doc.close();
                }
            }
            Toast.makeText(this, "Exportado ✔", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error al exportar: " + e, Toast.LENGTH_LONG).show();
        }
    }

    private synchronized void ensurePython() {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
    }

    private String readText(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo leer el archivo", Toast.LENGTH_LONG).show();
            return null;
        }
    }

    // ---- Render de SVG en el WebView (igual que el visor SVG) ----

    private void renderSvg(String svg) {
        String cleaned = svg
                .replaceFirst("(?s)^\\s*<\\?xml.*?\\?>", "")
                .replaceFirst("(?s)<!DOCTYPE[^>]*>", "");
        cleaned = makeResponsive(cleaned);

        String html =
                "<!DOCTYPE html><html><head>"
              + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
              + "<style>html,body{margin:0;padding:0;background:#fafafa;}"
              + "svg{display:block;width:100%;height:auto;}</style>"
              + "</head><body>" + cleaned + "</body></html>";

        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private String makeResponsive(String svg) {
        Matcher tag = Pattern.compile("(?is)<svg\\b([^>]*)>").matcher(svg);
        if (!tag.find()) {
            return svg;
        }
        String attrs = tag.group(1);

        String viewBox = attrValue(attrs, "viewBox");
        if (viewBox == null) {
            String w = numeric(attrValue(attrs, "width"));
            String h = numeric(attrValue(attrs, "height"));
            if (w != null && h != null) {
                viewBox = "0 0 " + w + " " + h;
            }
        }

        String newAttrs = attrs
                .replaceAll("(?is)\\s(width|height)\\s*=\\s*\"[^\"]*\"", "")
                .replaceAll("(?is)\\s(width|height)\\s*=\\s*'[^']*'", "");

        StringBuilder open = new StringBuilder("<svg").append(newAttrs);
        if (viewBox != null && !newAttrs.toLowerCase().contains("viewbox")) {
            open.append(" viewBox=\"").append(viewBox).append("\"");
        }
        open.append(">");

        return svg.substring(0, tag.start()) + open + svg.substring(tag.end());
    }

    private static String attrValue(String attrs, String name) {
        Matcher m = Pattern.compile("(?is)\\b" + name + "\\s*=\\s*[\"']([^\"']*)[\"']").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static String numeric(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(px)?\\s*$").matcher(value);
        return m.find() ? m.group(1) : null;
    }

    // SVG simple con un título y un subtítulo, para estados/avisos.
    private String messageSvg(String title, String subtitle) {
        return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 600 200'>"
             + "<rect width='600' height='200' fill='#fafafa'/>"
             + "<text x='300' y='90' text-anchor='middle' font-family='sans-serif'"
             + " font-size='28' font-weight='bold' fill='#333'>" + escape(title) + "</text>"
             + "<text x='300' y='130' text-anchor='middle' font-family='sans-serif'"
             + " font-size='16' fill='#666'>" + escape(subtitle) + "</text></svg>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
