val ffmpegDecoderAar = file("media3-ffmpeg-decoder-1.10.0-release.aar")

configurations.create("default")

artifacts {
    add("default", ffmpegDecoderAar)
}
