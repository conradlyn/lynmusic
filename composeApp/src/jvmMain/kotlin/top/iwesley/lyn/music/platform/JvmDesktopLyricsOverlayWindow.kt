package top.iwesley.lyn.music.platform

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import top.iwesley.lyn.music.core.model.DesktopLyricsPlatformService

internal interface JvmDesktopLyricsOverlayWindowAdapter {
    fun showText(text: String)
    fun hide()
    fun release()
    fun setCloseRequestHandler(handler: () -> Unit)
}

internal class JvmDesktopLyricsPlatformService(
    private val window: JvmDesktopLyricsOverlayWindowAdapter = AwtJvmDesktopLyricsOverlayWindow(),
) : DesktopLyricsPlatformService {
    private val mutableCloseRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val isSupported: Boolean = true
    override val consumesAppLyricsUpdates: Boolean = true
    override val closeRequests: Flow<Unit> = mutableCloseRequests.asSharedFlow()

    init {
        window.setCloseRequestHandler {
            mutableCloseRequests.tryEmit(Unit)
        }
    }

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
    private var closeButton: CloseOverlayButton? = null
    private var closeRequestHandler: (() -> Unit)? = null
    private var controlsVisible = false
    private var userMoved = false
    private var dragStartScreen: Point? = null
    private var dragStartWindow: Point? = null
    private val hideControlsTimer = Timer(1000) {
        setControlsVisible(false)
    }.apply {
        isRepeats = false
    }

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
            hideControlsTimer.stop()
            setControlsVisible(false)
            window?.isVisible = false
        }
    }

    override fun release() {
        runOnEdt {
            hideControlsTimer.stop()
            window?.dispose()
            window = null
            label = null
            closeButton = null
            userMoved = false
            controlsVisible = false
        }
    }

    override fun setCloseRequestHandler(handler: () -> Unit) {
        closeRequestHandler = handler
    }

    private fun ensureWindow(): JWindow {
        window?.let { return it }
        val nextLabel = JLabel("", SwingConstants.CENTER).apply {
            foreground = Color.WHITE
            font = font.deriveFont(22f)
            border = BorderFactory.createEmptyBorder(
                LABEL_VERTICAL_PADDING,
                LABEL_NORMAL_HORIZONTAL_PADDING,
                LABEL_VERTICAL_PADDING,
                LABEL_NORMAL_HORIZONTAL_PADDING,
            )
            minimumSize = Dimension(240, 56)
        }
        val nextCloseButton = CloseOverlayButton {
            hideControlsTimer.stop()
            setControlsVisible(false)
            window?.isVisible = false
            closeRequestHandler?.invoke()
        }.apply {
            addMouseListener(hoverMouseAdapter)
        }
        val panel = RoundedLyricsPanel(nextLabel, nextCloseButton)
        val nextWindow = JWindow().apply {
            isAlwaysOnTop = true
            background = Color(0, 0, 0, 0)
            contentPane = panel
            addWindowFocusListener(object : WindowAdapter() {
                override fun windowGainedFocus(event: WindowEvent) {
                    showControls()
                }

                override fun windowLostFocus(event: WindowEvent) {
                    scheduleHideControls()
                }
            })
            addMouseListener(dragMouseAdapter)
            addMouseMotionListener(dragMouseAdapter)
        }
        installDragHandlers(panel)
        installDragHandlers(nextLabel)
        label = nextLabel
        closeButton = nextCloseButton
        window = nextWindow
        return nextWindow
    }

    private val dragMouseAdapter = object : MouseAdapter() {
        override fun mouseEntered(event: MouseEvent) {
            showControls()
        }

        override fun mouseExited(event: MouseEvent) {
            scheduleHideControls()
        }

        override fun mousePressed(event: MouseEvent) {
            showControls()
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
            showControls()
        }
    }

    private val hoverMouseAdapter = object : MouseAdapter() {
        override fun mouseEntered(event: MouseEvent) {
            showControls()
        }

        override fun mouseExited(event: MouseEvent) {
            scheduleHideControls()
        }
    }

    private fun installDragHandlers(component: Component) {
        component.addMouseListener(dragMouseAdapter)
        component.addMouseMotionListener(dragMouseAdapter)
    }

    private fun showControls() {
        hideControlsTimer.stop()
        setControlsVisible(true)
    }

    private fun scheduleHideControls() {
        hideControlsTimer.restart()
    }

    private fun setControlsVisible(visible: Boolean) {
        if (controlsVisible == visible) return
        controlsVisible = visible
        closeButton?.isVisible = visible
        val target = window ?: return
        target.pack()
        if (!userMoved && target.isVisible) {
            placeBottomCenter(target)
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

    private companion object {
        const val LABEL_VERTICAL_PADDING = 12
        const val LABEL_NORMAL_HORIZONTAL_PADDING = 28
    }
}

private class CloseOverlayButton(
    private val onClose: () -> Unit,
) : JComponent() {
    private var pressed = false

    init {
        toolTipText = "关闭桌面歌词"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(24, 24)
        minimumSize = Dimension(24, 24)
        isOpaque = false
        isFocusable = true
        isVisible = false
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                pressed = contains(event.point)
            }

            override fun mouseReleased(event: MouseEvent) {
                val shouldClose = pressed && contains(event.point)
                pressed = false
                if (shouldClose) {
                    onClose()
                }
            }

            override fun mouseExited(event: MouseEvent) {
                pressed = false
            }
        })
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(255, 255, 255, 235)
            g.stroke = BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val size = 10
            val x = (width - size) / 2
            val y = (height - size) / 2
            val inset = 2
            g.drawLine(x + inset, y + inset, x + size - inset, y + size - inset)
            g.drawLine(x + size - inset, y + inset, x + inset, y + size - inset)
        } finally {
            g.dispose()
        }
    }
}

private class RoundedLyricsPanel(
    private val label: JLabel,
    private val closeButton: CloseOverlayButton,
) : JPanel(null) {
    init {
        isOpaque = false
        add(label)
        add(closeButton)
        setComponentZOrder(closeButton, 0)
        setComponentZOrder(label, 1)
    }

    override fun getPreferredSize(): Dimension {
        val labelSize = label.preferredSize
        return Dimension(
            labelSize.width.coerceAtLeast(240),
            labelSize.height.coerceAtLeast(56),
        )
    }

    override fun doLayout() {
        label.setBounds(0, 0, width, height)
        val buttonSize = closeButton.preferredSize
        closeButton.setBounds(4, 3, buttonSize.width, buttonSize.height)
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0, 0, 0, 150)
            g.fillRoundRect(0, 0, width, height, 28, 28)
        } finally {
            g.dispose()
        }
        super.paintComponent(graphics)
    }
}
