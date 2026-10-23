package dev.connectquick.controller.input

/**
 * A fixed key combination the phone can send (pressed in order, released in reverse). These are only
 * `input.key` messages, governed by the keyboard permission like any typed key: never a command.
 * Secure combinations Windows reserves (Ctrl+Alt+Supr, Win+L) are deliberately absent; they cannot
 * be injected anyway, and power/lock has its own permission.
 */
data class Shortcut(val label: String, val description: String, val keys: List<String>)

object Shortcuts {
    val editing = listOf(
        Shortcut("Copiar", "Ctrl+C", listOf("ControlLeft", "KeyC")),
        Shortcut("Pegar", "Ctrl+V", listOf("ControlLeft", "KeyV")),
        Shortcut("Cortar", "Ctrl+X", listOf("ControlLeft", "KeyX")),
        Shortcut("Deshacer", "Ctrl+Z", listOf("ControlLeft", "KeyZ")),
        Shortcut("Rehacer", "Ctrl+Y", listOf("ControlLeft", "KeyY")),
        Shortcut("Todo", "Seleccionar todo, Ctrl+A", listOf("ControlLeft", "KeyA")),
        Shortcut("Buscar", "Ctrl+F", listOf("ControlLeft", "KeyF")),
        Shortcut("Guardar", "Ctrl+S", listOf("ControlLeft", "KeyS")),
    )

    val windows = listOf(
        Shortcut("Alt+Tab", "Cambiar de ventana", listOf("AltLeft", "Tab")),
        Shortcut("Cerrar", "Cerrar la ventana, Alt+F4", listOf("AltLeft", "F4")),
        Shortcut("Escritorio", "Mostrar el escritorio, Win+D", listOf("MetaLeft", "KeyD")),
        Shortcut("Explorador", "Abrir el Explorador, Win+E", listOf("MetaLeft", "KeyE")),
        Shortcut("Recorte", "Captura de una zona, Win+Shift+S", listOf("MetaLeft", "ShiftLeft", "KeyS")),
        Shortcut("Tareas", "Administrador de tareas, Ctrl+Shift+Esc", listOf("ControlLeft", "ShiftLeft", "Escape")),
        Shortcut("Inicio", "Menú Inicio, tecla Windows", listOf("MetaLeft")),
        Shortcut("Pantalla completa", "F11", listOf("F11")),
    )

    /** Windows touchpad conventions: up = Task View, down = desktop, sideways = switch app. */
    fun forGesture(g: TouchGesture): Shortcut = when (g) {
        TouchGesture.SWIPE_UP -> Shortcut("Vista de tareas", "Win+Tab", listOf("MetaLeft", "Tab"))
        TouchGesture.SWIPE_DOWN -> Shortcut("Escritorio", "Win+D", listOf("MetaLeft", "KeyD"))
        TouchGesture.SWIPE_RIGHT -> Shortcut("Siguiente app", "Alt+Tab", listOf("AltLeft", "Tab"))
        TouchGesture.SWIPE_LEFT -> Shortcut("App anterior", "Alt+Shift+Tab", listOf("AltLeft", "ShiftLeft", "Tab"))
    }

    val functionKeys: List<String> = (1..12).map { "F$it" }

    /** The keys of the bar shown right above the phone's keyboard. Label to code. */
    val keyBar = listOf(
        "Esc" to "Escape", "Tab" to "Tab", "←" to "ArrowLeft", "↑" to "ArrowUp", "↓" to "ArrowDown", "→" to "ArrowRight",
        "Supr" to "Delete", "Inicio" to "Home", "Fin" to "End", "RePág" to "PageUp", "AvPág" to "PageDown",
    )
}
