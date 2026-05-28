import AppKit
import Foundation
import MediaPlayer

public typealias LynMusicNowPlayingCallback = @convention(c) (Int32, Double) -> Void

private enum LynMusicNowPlayingCommand: Int32 {
    case play = 1
    case pause = 2
    case togglePlayPause = 3
    case next = 4
    case previous = 5
    case seek = 6
}

private final class LynMusicNowPlayingController {
    private let callback: LynMusicNowPlayingCallback
    private let infoCenter = MPNowPlayingInfoCenter.default()
    private let commandCenter = MPRemoteCommandCenter.shared()
    private var commandTargets: [(MPRemoteCommand, Any)] = []
    private var currentArtworkImage: NSImage?
    private var currentArtwork: MPMediaItemArtwork?
    private var isDisposed = false

    init(callback: @escaping LynMusicNowPlayingCallback) {
        self.callback = callback
        runOnMainSync {
            self.installRemoteCommands()
            self.updateCommandAvailability(hasTrack: false, canSeek: false, hasNext: false, hasPrevious: false)
        }
    }

    func update(
        title: String?,
        artist: String?,
        album: String?,
        artworkPath: String?,
        durationMs: Int64,
        positionMs: Int64,
        isPlaying: Bool,
        canSeek: Bool,
        hasNext: Bool,
        hasPrevious: Bool
    ) {
        runOnMainSync {
            guard !self.isDisposed else { return }
            guard let title = title?.trimmedNonEmpty else {
                self.clear()
                return
            }

            self.updateArtwork(path: artworkPath?.trimmedNonEmpty)

            var info: [String: Any] = [
                MPMediaItemPropertyTitle: title,
                MPMediaItemPropertyPlaybackDuration: max(Double(durationMs), 0.0) / 1000.0,
                MPNowPlayingInfoPropertyElapsedPlaybackTime: max(Double(positionMs), 0.0) / 1000.0,
                MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
                MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            ]
            if let artist = artist?.trimmedNonEmpty {
                info[MPMediaItemPropertyArtist] = artist
            }
            if let album = album?.trimmedNonEmpty {
                info[MPMediaItemPropertyAlbumTitle] = album
            }
            if let artwork = self.currentArtwork {
                info[MPMediaItemPropertyArtwork] = artwork
            }

            self.infoCenter.nowPlayingInfo = info
            if #available(macOS 10.12.2, *) {
                self.infoCenter.playbackState = isPlaying ? .playing : .paused
            }
            self.updateCommandAvailability(hasTrack: true, canSeek: canSeek, hasNext: hasNext, hasPrevious: hasPrevious)
        }
    }

    func clear() {
        runOnMainSync {
            guard !self.isDisposed else { return }
            self.infoCenter.nowPlayingInfo = nil
            if #available(macOS 10.12.2, *) {
                self.infoCenter.playbackState = .stopped
            }
            self.currentArtwork = nil
            self.currentArtworkImage = nil
            self.updateCommandAvailability(hasTrack: false, canSeek: false, hasNext: false, hasPrevious: false)
        }
    }

    func dispose() {
        runOnMainSync {
            guard !self.isDisposed else { return }
            self.clear()
            self.commandTargets.forEach { command, target in
                command.removeTarget(target)
            }
            self.commandTargets.removeAll()
            self.isDisposed = true
        }
    }

    private func installRemoteCommands() {
        register(commandCenter.playCommand, command: .play)
        register(commandCenter.pauseCommand, command: .pause)
        register(commandCenter.togglePlayPauseCommand, command: .togglePlayPause)
        register(commandCenter.nextTrackCommand, command: .next)
        register(commandCenter.previousTrackCommand, command: .previous)
        register(commandCenter.changePlaybackPositionCommand, command: .seek)
    }

    private func register(_ remoteCommand: MPRemoteCommand, command: LynMusicNowPlayingCommand) {
        let target = remoteCommand.addTarget { [weak self] event in
            guard let self = self, !self.isDisposed else {
                return .commandFailed
            }
            if command == .seek {
                guard let positionEvent = event as? MPChangePlaybackPositionCommandEvent else {
                    return .commandFailed
                }
                self.callback(command.rawValue, positionEvent.positionTime)
            } else {
                self.callback(command.rawValue, 0.0)
            }
            return .success
        }
        commandTargets.append((remoteCommand, target))
    }

    private func updateCommandAvailability(hasTrack: Bool, canSeek: Bool, hasNext: Bool, hasPrevious: Bool) {
        commandCenter.playCommand.isEnabled = hasTrack
        commandCenter.pauseCommand.isEnabled = hasTrack
        commandCenter.togglePlayPauseCommand.isEnabled = hasTrack
        commandCenter.nextTrackCommand.isEnabled = hasTrack && hasNext
        commandCenter.previousTrackCommand.isEnabled = hasTrack && hasPrevious
        commandCenter.changePlaybackPositionCommand.isEnabled = hasTrack && canSeek
    }

    private func updateArtwork(path: String?) {
        guard let path = path, let image = NSImage(contentsOfFile: path), image.isValid else {
            currentArtwork = nil
            currentArtworkImage = nil
            return
        }
        let size = image.size.width > 0 && image.size.height > 0
            ? image.size
            : NSSize(width: 512, height: 512)
        currentArtworkImage = image
        currentArtwork = MPMediaItemArtwork(boundsSize: size) { _ in
            image
        }
    }
}

private extension String {
    var trimmedNonEmpty: String? {
        let value = trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }
}

private func runOnMainSync(_ block: @escaping () -> Void) {
    if Thread.isMainThread {
        block()
    } else {
        DispatchQueue.main.sync(execute: block)
    }
}

@_cdecl("lyn_music_now_playing_create")
public func lyn_music_now_playing_create(_ callback: LynMusicNowPlayingCallback?) -> UnsafeMutableRawPointer? {
    guard let callback = callback else { return nil }
    let controller = LynMusicNowPlayingController(callback: callback)
    return Unmanaged.passRetained(controller).toOpaque()
}

@_cdecl("lyn_music_now_playing_update")
public func lyn_music_now_playing_update(
    _ handle: UnsafeMutableRawPointer?,
    _ title: UnsafePointer<CChar>?,
    _ artist: UnsafePointer<CChar>?,
    _ album: UnsafePointer<CChar>?,
    _ artworkPath: UnsafePointer<CChar>?,
    _ durationMs: Int64,
    _ positionMs: Int64,
    _ isPlaying: Int32,
    _ canSeek: Int32,
    _ hasNext: Int32,
    _ hasPrevious: Int32
) -> Int32 {
    guard let handle = handle else { return 0 }
    let controller = Unmanaged<LynMusicNowPlayingController>.fromOpaque(handle).takeUnretainedValue()
    controller.update(
        title: title.map(String.init(cString:)),
        artist: artist.map(String.init(cString:)),
        album: album.map(String.init(cString:)),
        artworkPath: artworkPath.map(String.init(cString:)),
        durationMs: durationMs,
        positionMs: positionMs,
        isPlaying: isPlaying != 0,
        canSeek: canSeek != 0,
        hasNext: hasNext != 0,
        hasPrevious: hasPrevious != 0
    )
    return 1
}

@_cdecl("lyn_music_now_playing_clear")
public func lyn_music_now_playing_clear(_ handle: UnsafeMutableRawPointer?) -> Int32 {
    guard let handle = handle else { return 0 }
    let controller = Unmanaged<LynMusicNowPlayingController>.fromOpaque(handle).takeUnretainedValue()
    controller.clear()
    return 1
}

@_cdecl("lyn_music_now_playing_dispose")
public func lyn_music_now_playing_dispose(_ handle: UnsafeMutableRawPointer?) -> Int32 {
    guard let handle = handle else { return 0 }
    let controller = Unmanaged<LynMusicNowPlayingController>.fromOpaque(handle).takeRetainedValue()
    controller.dispose()
    return 1
}
