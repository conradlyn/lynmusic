package top.iwesley.lyn.music.platform

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import top.iwesley.lyn.music.core.model.DesktopLyricsPlatformService

internal interface JvmDesktopLyricsOverlayWindowAdapter {
    fun showText(text: String)
    fun hide()
    fun release()
}

internal class JvmDesktopLyricsPlatformService(
    private val window: JvmDesktopLyricsOverlayWindowAdapter = AwtJvmDesktopLyricsOverlayWindow(),
) : DesktopLyricsPlatformService {
    override val isSupported: Boolean = true
    override val consumesAppLyricsUpdates: Boolean = true

    override fun hasOverlayPermission(): Boolean = true

    override suspend fun requestOverlayPermission(): Boolean = true

    override suspend fun setDesktopLyricsEnabled(enabled: Boolean) {
        if (!enabled) {
            window.hide()
        }
    }

    override suspend fun updateLyrics(text: String) {
        window.showText(text)
    }

    override suspend fun hideLyrics() {
        window.hide()
    }

    override suspend fun release() {
        window.release()
    }
}

private class AwtJvmDesktopLyricsOverlayWindow : JvmDesktopLyricsOverlayWindowAdapter {
    private var window: JWindow? = null
    private var label: JLabel? = null
    private var userMoved = false
    private var dragStartScreen: Point? = null
    private var dragStartWindow: Point? = null

    override fun showText(text: String) {
        runOnEdt {
            if (GraphicsEnvironment.isHeadless()) return@runOnEdt
            val resolvedWindow = ensureWindow()
            label?.text = text
            resolvedWindow.pack()
            if (!userMoved) {
                placeBottomCenter(resolvedWindow)
            }
            resolvedWindow.isVisible = true
        }
    }

    override fun hide() {
        runOnEdt {
            window?.isVisible = false
        }
    }

    override fun release() {
        runOnEdt {
            window?.dispose()
            window = null
            label = null
            userMoved = false
        }
    }

    private fun ensureWindow(): JWindow {
        window?.let { return it }
        val nextLabel = JLabel("", SwingConstants.CENTER).apply {
            foreground = Color.WHITE
            font = font.deriveFont(22f)
            border = BorderFactory.createEmptyBorder(12, 28, 12, 28)
            minimumSize = Dimension(240, 56)
        }
        val panel = RoundedLyricsPanel().apply {
            layout = BorderLayout()
            add(nextLabel, BorderLayout.CENTER)
        }
        val nextWindow = JWindow().apply {
            isAlwaysOnTop = true
            background = Color(0, 0, 0, 0)
            contentPane = panel
            addMouseListener(dragMouseAdapter)
            addMouseMotionListener(dragMouseAdapter)
        }
        label = nextLabel
        window = nextWindow
        return nextWindow
    }

    private val dragMouseAdapter = object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) {
            dragStartScreen = event.locationOnScreen
            dragStartWindow = window?.location
        }

        override fun mouseDragged(event: MouseEvent) {
            val startScreen = dragStartScreen ?: return
            val startWindow = dragStartWindow ?: return
            val current = event.locationOnScreen
            window?.location = Point(
                startWindow.x + current.x - startScreen.x,
                startWindow.y + current.y - startScreen.y,
            )
            userMoved = true
        }
    }

    private fun placeBottomCenter(target: JWindow) {
        val bounds = GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .defaultConfiguration
            .bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(target.graphicsConfiguration)
        val x = bounds.x + (bounds.width - target.width) / 2
        val y = bounds.y + bounds.height - insets.bottom - target.height - 96
        target.setLocation(x.coerceAtLeast(bounds.x), y.coerceAtLeast(bounds.y))
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeLater(block)
        }
    }
}

private class RoundedLyricsPanel : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0, 0, 0, 178)
            g.fillRoundRect(0, 0, width, height, 28, 28)
        } finally {
            g.dispose()
        }
        super.paintComponent(graphics)
    }
}
