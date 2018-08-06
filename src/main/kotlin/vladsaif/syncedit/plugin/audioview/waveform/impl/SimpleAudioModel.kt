package vladsaif.syncedit.plugin.audioview.waveform.impl

import vladsaif.syncedit.plugin.ClosedIntRange
import vladsaif.syncedit.plugin.ClosedLongRange
import vladsaif.syncedit.plugin.audioview.AudioSampler
import vladsaif.syncedit.plugin.audioview.waveform.AudioDataModel
import vladsaif.syncedit.plugin.audioview.waveform.AveragedSampleData
import vladsaif.syncedit.plugin.audioview.waveform.toDecodeFormat
import vladsaif.syncedit.plugin.floorToInt
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * @constructor
 * @throws UnsupportedAudioFileException If audio file cannot be recognized by audio system
 * or if it cannot be converted to decode format.
 * @throws java.io.IOException If I/O error occurs.
 */
class SimpleAudioModel(file: Path) : AudioDataModel {
    private val myFile = file.toAbsolutePath().toFile()
    override var trackDurationMilliseconds = 0.0
        private set
    override var millisecondsPerFrame = 0.0
        private set
    override var totalFrames = 0L
        private set
    override var framesPerMillisecond = 0.0
        private set

    init {
        AudioSystem.getAudioInputStream(this.myFile).use {
            if (!AudioSystem.isConversionSupported(it.format.toDecodeFormat(), it.format)) {
                throw UnsupportedAudioFileException("Cannot decode audio file.")
            }
            AudioSystem.getAudioInputStream(it.format.toDecodeFormat(), it).use {
                val audio = AudioSampler(it)
                var sampleCount = 0L
                audio.forEachSample { sampleCount++ }
                totalFrames = sampleCount / it.format.channels
                millisecondsPerFrame = 1000.0 / it.format.frameRate
                framesPerMillisecond = it.format.frameRate / 1000.0
                trackDurationMilliseconds = totalFrames * 1000L / it.format.frameRate.toDouble()
            }
        }
    }

    override fun msRangeToFrameRange(range: ClosedIntRange): ClosedLongRange {
        return ClosedLongRange(
                (framesPerMillisecond * range.start).toLong(),
                (framesPerMillisecond * range.end).toLong()
        )
    }

    override fun frameRangeToMsRange(range: ClosedLongRange): ClosedIntRange {
        return ClosedIntRange(
                (millisecondsPerFrame * range.start).toInt(),
                (millisecondsPerFrame * range.end).toInt()
        )
    }

    override fun getStartFrame(maxChunks: Int, chunk: Int): Long {
        val framesPerChunk = (totalFrames / maxChunks).toInt()
        val bigChunkRange = getBigChunkRange(maxChunks)
        return if (chunk in bigChunkRange) {
            chunk.toLong() * (framesPerChunk + 1)
        } else {
            chunk.toLong() * framesPerChunk + bigChunkRange.length
        }
    }

    override fun getAveragedSampleData(maxChunks: Int, chunkRange: ClosedIntRange, isActive: AtomicBoolean): List<AveragedSampleData> {
        val framesPerChunk = (totalFrames / maxChunks).toInt()
        AudioSystem.getAudioInputStream(myFile).use { input ->
            val decodeFormat = input.format.toDecodeFormat()
            val chunks = chunkRange.length
            val ret = List(decodeFormat.channels) {
                AveragedSampleData(chunks, chunkRange.start, decodeFormat.sampleSizeInBits)
            }
            if (chunks == 0) return ret
            AudioSampler(AudioSystem.getAudioInputStream(decodeFormat, input),
                    countSkippedFrames(maxChunks, chunkRange, framesPerChunk),
                    countReadFrames(maxChunks, chunkRange, framesPerChunk)).use {
                countStat(it, framesPerChunk, ret, maxChunks, chunkRange, decodeFormat.channels, isActive)
            }
            return ret
        }
    }

    override fun getChunk(maxChunks: Int, frame: Long): Int {
        val bigChunkRange = getBigChunkRange(maxChunks)
        val framesPerChunk = (totalFrames / maxChunks).toInt()
        val res = if (frame >= bigChunkRange.length * (framesPerChunk + 1)) {
            (bigChunkRange.length + (frame - bigChunkRange.length * (framesPerChunk + 1)) / framesPerChunk)
        } else {
            (frame / (framesPerChunk + 1))
        }
        return res.floorToInt()
    }

    private fun countStat(audio: AudioSampler,
                          framesPerChunk: Int,
                          data: List<AveragedSampleData>,
                          maxChunks: Int,
                          chunkRange: ClosedIntRange,
                          channels: Int,
                          isActive: AtomicBoolean) {
        val peaks = List(channels) { LongArray(framesPerChunk + 1) }
        var restCounter = getBigChunkRange(maxChunks).intersect(chunkRange).length
        var frameCounter = 0
        var channelCounter = 0
        var chunkCounter = 0
        audio.forEachSample {
            if (!isActive.get()) throw CancellationException()
            peaks[channelCounter++][frameCounter] = it
            if (channelCounter == channels) {
                channelCounter = 0
                frameCounter++
                if (frameCounter == framesPerChunk && restCounter <= 0 ||
                        frameCounter == framesPerChunk + 1 && restCounter > 0) {
                    restCounter--
                    data.forEachIndexed { index, x -> x.setChunk(frameCounter, chunkCounter, peaks[index]) }
                    chunkCounter++
                    frameCounter = 0
                }
            }
        }
        if (frameCounter != 0) {
            println(frameCounter)
            data.forEachIndexed { index, x -> x.setChunk(frameCounter, chunkCounter, peaks[index]) }
        }
    }

    private fun getBigChunkRange(maxChunks: Int) = ClosedIntRange(0, (totalFrames % maxChunks).toInt() - 1)

    private fun countReadFrames(maxChunks: Int, chunkRange: ClosedIntRange, framesPerChunk: Int): Long {
        var sum = 0L
        val bigChunks = getBigChunkRange(maxChunks)
        sum += chunkRange.intersect(bigChunks).length * (framesPerChunk + 1)
        sum += chunkRange.intersect(ClosedIntRange(bigChunks.end + 1, Int.MAX_VALUE)).length * framesPerChunk
        return sum
    }

    private fun countSkippedFrames(maxChunks: Int, chunkRange: ClosedIntRange, framesPerChunk: Int): Long {
        val bigChunkRange = getBigChunkRange(maxChunks)
        val skipRange = ClosedIntRange(0, chunkRange.start - 1)
        return bigChunkRange.intersect(skipRange).length * (framesPerChunk.toLong() + 1) +
                ClosedIntRange(bigChunkRange.end + 1, chunkRange.start - 1).intersect(skipRange).length * framesPerChunk.toLong()
    }

    private fun AveragedSampleData.setChunk(counter: Int, chunk: Int, peaks: LongArray) {
        var sum = 0L
        for (j in 0 until counter) {
            sum += peaks[j]
        }
        val average = sum / counter
        var max = Long.MIN_VALUE
        for (j in 0 until counter) {
            max = max(max, peaks[j])
        }
        var min = Long.MAX_VALUE
        for (j in 0 until counter) {
            min = min(min, peaks[j])
        }
        val rmsSquared = peaks.fold(0L) { acc, x ->
            acc + (x - average) * (x - average)
        }.toDouble() / counter
        averagePeaks[chunk] = average
        rootMeanSquare[chunk] = sqrt(rmsSquared).toLong()
        highestPeaks[chunk] = max
        lowestPeaks[chunk] = min
    }
}