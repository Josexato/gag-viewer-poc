package com.poc.gagviewer;

import android.app.Activity;
import android.content.Intent;
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

    private WebView webView;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        Button openButton = new Button(this);
        openButton.setText("Abrir .gag / .sdjf / .svg");
        openButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPicker();
            }
        });
        root.addView(openButton, new LinearLayout.LayoutParams(
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
        if (requestCode == REQUEST_OPEN && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            loadFrom(data.getData());
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
                        renderSvg(finalSvg);
                    }
                });
            }
        }).start();
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
