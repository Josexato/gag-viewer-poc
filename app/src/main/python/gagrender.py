"""Puente entre Android y el motor AlmaGag.

Recibe el texto de un .gag/.sdjf, lo procesa con el motor real
(generate_diagram) y devuelve el SVG resultante como texto.
"""
import os
import traceback

from AlmaGag.generator import generate_diagram


def render(gag_text, work_dir):
    """Convierte el contenido de un .gag/.sdjf en SVG.

    work_dir debe ser un directorio escribible (filesDir de la app).
    Devuelve el SVG como str, o un SVG de error legible si algo falla.
    """
    in_path = os.path.join(work_dir, "input.sdjf")
    out_path = os.path.join(work_dir, "output.svg")
    try:
        with open(in_path, "w", encoding="utf-8") as f:
            f.write(gag_text)

        ok = generate_diagram(in_path, output_file=out_path)
        if not ok:
            return _error_svg("El motor no pudo generar el diagrama "
                              "(¿JSON inválido o tipo no soportado?).")

        with open(out_path, "r", encoding="utf-8") as f:
            return f.read()
    except Exception as e:  # noqa: BLE001 - queremos mostrar cualquier fallo
        return _error_svg("Error al renderizar:\n" + repr(e),
                          detail=traceback.format_exc())


def _error_svg(message, detail=""):
    """SVG mínimo que muestra un mensaje de error en pantalla."""
    lines = (message + "\n" + detail).splitlines()[:18]
    tspans = "".join(
        "<tspan x='20' dy='22'>" + _escape(line[:80]) + "</tspan>"
        for line in lines
    )
    return (
        "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 640 480'>"
        "<rect width='640' height='480' fill='#fff3f0'/>"
        "<text x='20' y='20' font-family='monospace' font-size='14' fill='#b00020'>"
        + tspans +
        "</text></svg>"
    )


def _escape(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
