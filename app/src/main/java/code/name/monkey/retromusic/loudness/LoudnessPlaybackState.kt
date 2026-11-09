package code.name.monkey.retromusic.loudness

/** Process-wide attenuation (0..1) applied inside [code.name.monkey.retromusic.service.AudioManagerPlayback.scaledVolume]. */
object LoudnessPlaybackState {
    @Volatile
    var attenuationLinear: Float = 1f
}
