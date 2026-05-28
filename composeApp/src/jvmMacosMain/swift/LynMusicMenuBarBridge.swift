import AppKit
import Foundation

public typealias LynMusicMenuBarCallback = @convention(c) (Int32) -> Void

private enum LynMusicMenuBarCommand: Int32 {
    case previous = 1
    case togglePlayPause = 2
    case next = 3
}

private final class LynMusicMenuBarController: NSObject {
    private let callback: LynMusicMenuBarCallback
    private var lyricsItem: NSStatusItem?
    private var previousItem: NSStatusItem?
    private var playPauseItem: NSStatusItem?
    private var nextItem: NSStatusItem?
    private var isEnabled = false
    private var lyricsText: String?
    private var hasTrack = false
    private var isPlaying = false
    private var hasPrevious = false
    private var hasNext = false
    private var lyricsScrollTimer: Timer?
    private var lyricsScrollOffset = 0
    private var isDisposed = false

    init(callback: @escaping LynMusicMenuBarCallback) {
        self.callback = callback
        super.init()
    }

    func setEnabled(_ enabled: Bool) {
        runMenuBarOnMainSync {
            guard !self.isDisposed else { return }
            self.isEnabled = enabled
            self.render()
        }
    }

    func updateLyrics(_ text: String?) {
        runMenuBarOnMainSync {
            guard !self.isDisposed else { return }
            let nextText = menuBarTrimmedNonEmpty(text)
            if self.lyricsText != nextText {
                self.lyricsText = nextText
                self.resetLyricsScroll()
            }
            self.render()
        }
    }

    func updatePlaybackState(hasTrack: Bool, isPlaying: Bool, hasPrevious: Bool, hasNext: Bool) {
        runMenuBarOnMainSync {
            guard !self.isDisposed else { return }
            self.hasTrack = hasTrack
            self.isPlaying = isPlaying
            self.hasPrevious = hasPrevious
            self.hasNext = hasNext
            self.render()
        }
    }

    func dispose() {
        runMenuBarOnMainSync {
            guard !self.isDisposed else { return }
            self.removeStatusItems()
            self.isDisposed = true
        }
    }

    private func render() {
        guard isEnabled else {
            removeStatusItems()
            return
        }
        ensureStatusItems()
        updateLyricsButton()
        updateControlButtons()
    }

    private func ensureStatusItems() {
        if nextItem == nil {
            nextItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
            configureControlItem(nextItem, action: #selector(nextAction))
        }
        if playPauseItem == nil {
            playPauseItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
            configureControlItem(playPauseItem, action: #selector(togglePlayPauseAction))
        }
        if previousItem == nil {
            previousItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
            configureControlItem(previousItem, action: #selector(previousAction))
        }
        if lyricsItem == nil {
            lyricsItem = NSStatusBar.system.statusItem(withLength: LYRICS_ITEM_WIDTH)
            configureLyricsItem(lyricsItem)
        }
    }

    private func configureControlItem(_ item: NSStatusItem?, action: Selector) {
        guard let button = item?.button else { return }
        button.target = self
        button.action = action
        button.imagePosition = .imageOnly
    }

    private func configureLyricsItem(_ item: NSStatusItem?) {
        guard let button = item?.button else { return }
        button.image = nil
        button.imagePosition = .noImage
        button.alignment = .center
        button.cell?.lineBreakMode = .byClipping
        button.cell?.usesSingleLineMode = true
    }

    private func updateLyricsButton() {
        guard let button = lyricsItem?.button else { return }
        let text = lyricsText ?? DEFAULT_LYRICS_TEXT
        button.toolTip = text
        if shouldScrollLyrics(text) {
            setLyricsButtonTitle(scrollingLyricsText(text, offset: lyricsScrollOffset))
            startLyricsScrollTimer()
        } else {
            stopLyricsScrollTimer()
            lyricsScrollOffset = 0
            setLyricsButtonTitle(text)
        }
    }

    private func setLyricsButtonTitle(_ value: String) {
        guard let button = lyricsItem?.button else { return }
        let paragraphStyle = NSMutableParagraphStyle()
        paragraphStyle.lineBreakMode = .byClipping
        paragraphStyle.alignment = .center
        button.attributedTitle = NSAttributedString(
            string: value,
            attributes: [
                .paragraphStyle: paragraphStyle,
                .font: button.font ?? NSFont.menuBarFont(ofSize: 0),
                .foregroundColor: NSColor.labelColor,
            ]
        )
    }

    private func updateControlButtons() {
        updateControlButton(
            item: previousItem,
            symbolName: "backward.fill",
            fallbackTitle: "|<",
            tooltip: "上一首",
            isEnabled: hasPrevious
        )
        updateControlButton(
            item: playPauseItem,
            symbolName: isPlaying ? "pause.fill" : "play.fill",
            fallbackTitle: isPlaying ? "||" : ">",
            tooltip: isPlaying ? "暂停" : "播放",
            isEnabled: hasTrack
        )
        updateControlButton(
            item: nextItem,
            symbolName: "forward.fill",
            fallbackTitle: ">|",
            tooltip: "下一首",
            isEnabled: hasNext
        )
    }

    private func updateControlButton(
        item: NSStatusItem?,
        symbolName: String,
        fallbackTitle: String,
        tooltip: String,
        isEnabled: Bool
    ) {
        guard let button = item?.button else { return }
        if #available(macOS 11.0, *) {
            let image = NSImage(systemSymbolName: symbolName, accessibilityDescription: tooltip)
            image?.isTemplate = true
            button.image = image
            button.title = ""
        } else {
            button.image = nil
            button.title = fallbackTitle
        }
        button.toolTip = tooltip
        button.isEnabled = isEnabled
    }

    private func removeStatusItems() {
        stopLyricsScrollTimer()
        lyricsScrollOffset = 0
        [lyricsItem, previousItem, playPauseItem, nextItem].forEach { item in
            if let item = item {
                NSStatusBar.system.removeStatusItem(item)
            }
        }
        lyricsItem = nil
        previousItem = nil
        playPauseItem = nil
        nextItem = nil
    }

    private func resetLyricsScroll() {
        lyricsScrollOffset = 0
        stopLyricsScrollTimer()
    }

    private func startLyricsScrollTimer() {
        guard lyricsScrollTimer == nil else { return }
        let timer = Timer(timeInterval: LYRICS_SCROLL_INTERVAL, repeats: true) { [weak self] _ in
            self?.advanceLyricsScroll()
        }
        RunLoop.main.add(timer, forMode: .common)
        lyricsScrollTimer = timer
    }

    private func stopLyricsScrollTimer() {
        lyricsScrollTimer?.invalidate()
        lyricsScrollTimer = nil
    }

    private func advanceLyricsScroll() {
        guard isEnabled, lyricsItem != nil, let lyricsText = lyricsText, shouldScrollLyrics(lyricsText) else {
            stopLyricsScrollTimer()
            updateLyricsButton()
            return
        }
        lyricsScrollOffset = (lyricsScrollOffset + 1) % scrollingLyricsCharacterCount(lyricsText)
        setLyricsButtonTitle(scrollingLyricsText(lyricsText, offset: lyricsScrollOffset))
    }

    @objc private func previousAction() {
        callback(LynMusicMenuBarCommand.previous.rawValue)
    }

    @objc private func togglePlayPauseAction() {
        callback(LynMusicMenuBarCommand.togglePlayPause.rawValue)
    }

    @objc private func nextAction() {
        callback(LynMusicMenuBarCommand.next.rawValue)
    }
}

private func menuBarTrimmedNonEmpty(_ value: String?) -> String? {
    let trimmed = value?
        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    return trimmed.isEmpty ? nil : trimmed
}

private func shouldScrollLyrics(_ value: String) -> Bool {
    return value.count > LYRICS_VISIBLE_CHARACTER_COUNT
}

private func scrollingLyricsText(_ value: String, offset: Int) -> String {
    let characters = Array(value + LYRICS_SCROLL_GAP)
    guard !characters.isEmpty else { return "" }
    let safeOffset = max(offset, 0)
    var result = ""
    for index in 0..<LYRICS_VISIBLE_CHARACTER_COUNT {
        result.append(characters[(safeOffset + index) % characters.count])
    }
    return result
}

private func scrollingLyricsCharacterCount(_ value: String) -> Int {
    return max(Array(value + LYRICS_SCROLL_GAP).count, 1)
}

private let LYRICS_ITEM_WIDTH: CGFloat = 220
private let LYRICS_SCROLL_INTERVAL: TimeInterval = 0.5
private let LYRICS_VISIBLE_CHARACTER_COUNT = 18
private let LYRICS_SCROLL_GAP = "      "
private let DEFAULT_LYRICS_TEXT = "LynMusic"

private func runMenuBarOnMainSync(_ block: @escaping () -> Void) {
    if Thread.isMainThread {
        block()
    } else {
        DispatchQueue.main.sync(execute: block)
    }
}

@_cdecl("lyn_music_menu_bar_create")
public func lyn_music_menu_bar_create(_ callback: LynMusicMenuBarCallback?) -> UnsafeMutableRawPointer? {
    guard let callback = callback else { return nil }
    let controller = LynMusicMenuBarController(callback: callback)
    return Unmanaged.passRetained(controller).toOpaque()
}

@_cdecl("lyn_music_menu_bar_set_enabled")
public func lyn_music_menu_bar_set_enabled(_ handle: UnsafeMutableRawPointer?, _ enabled: Int32) -> Int32 {
    guard let handle = handle else { return 0 }
    let controller = Unmanaged<LynMusicMenuBarController>.fromOpaque(handle).takeUnretainedValue()
    controller.setEnabled(enabled != 0)
    return 1
}

@_cdecl("lyn_music_menu_bar_update_lyrics")
public func lyn_music_menu_bar_update_lyrics(
    _ handle: UnsafeMutableRawPointer?,
    _ lyrics: UnsafePointer<CChar>?
) -> Int32 {
    guard let handle = handle else { return 0 }
    let controller = Unmanaged<LynMusicMenuBarController>.fromOpaque(handle).takeUnretainedValue()
    controller.updateLyrics(lyrics.map(String.init(cString:)))
    return 1
}

@_cdecl("lyn_music_menu_bar_update_playback_state")
public func lyn_music_menu_bar_update_playback_state(
    _ handle: UnsafeMutableRawPointer?,
    _ hasTrack: Int32,
    _ isPlaying: Int32,
    _ hasPrevious: Int32,
    _ hasNext: Int32
) -> Int32 {
    guard let handle = handle else { return 0 }
    let controller = Unmanaged<LynMusicMenuBarController>.fromOpaque(handle).takeUnretainedValue()
    controller.updatePlaybackState(
        hasTrack: hasTrack != 0,
        isPlaying: isPlaying != 0,
        hasPrevious: hasPrevious != 0,
        hasNext: hasNext != 0
    )
    return 1
}

@_cdecl("lyn_music_menu_bar_dispose")
public func lyn_music_menu_bar_dispose(_ handle: UnsafeMutableRawPointer?) -> Int32 {
    guard let handle = handle else { return 0 }
    let controller = Unmanaged<LynMusicMenuBarController>.fromOpaque(handle).takeRetainedValue()
    controller.dispose()
    return 1
}
