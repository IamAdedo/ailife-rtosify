package com.ailife.rtosify.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Utility class to compress video files for the watch.
 * Reduces resolution to 480p (or lower) and bitrate to ~1Mbps.
 */
object VideoCompressor {
    private const val TAG = "VideoCompressor"
    private const val TIMEOUT_USEC = 2500L
    private const val I_FRAME_INTERVAL = 5 // Sync frame every 5 seconds
    private const val COMPRESSED_HEIGHT = 480
    private const val COMPRESSED_BITRATE = 500 * 1024 // 1 Mbps

    fun compressVideo(inputPath: String, outputPath: String): Boolean {
        Log.d(TAG, "Starting video compression: $inputPath -> $outputPath")
        
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        
        try {
            val inputFile = File(inputPath)
            if (!inputFile.exists() || !inputFile.canRead()) {
                Log.e(TAG, "Input file not found or not readable")
                return false
            }

            extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            val trackIndex = selectTrack(extractor, "video/")
            if (trackIndex < 0) {
                Log.e(TAG, "No video track found in $inputPath")
                return false
            }
            extractor.selectTrack(trackIndex)

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val width = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
            
            // Calculate new dimensions (keep aspect ratio)
            val newHeight = if (height > COMPRESSED_HEIGHT) COMPRESSED_HEIGHT else height
            val newWidth = (width * newHeight / height) / 2 * 2 // Ensure even width

            Log.d(TAG, "Original: ${width}x${height}, Target: ${newWidth}x${newHeight}")

            // If already small enough, maybe just copy? For now, we always compress to ensure bitrate is low.
            
            // Prepare Encoder
            val outputFormat = MediaFormat.createVideoFormat("video/avc", newWidth, newHeight)
            outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, COMPRESSED_BITRATE)
            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)

            encoder = MediaCodec.createEncoderByType("video/avc")
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            // Prepare Decoder
            decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            val surface = com.ailife.rtosify.media.OutputSurface(inputSurface) // We need an EGL wrapper to link decoder output to encoder input
            decoder.configure(inputFormat, surface.surface, null, 0)
            decoder.start()

            // Process
            doVideoConversion(extractor, decoder, encoder, muxerStartWrapper = { outputTrackIndex, format ->
                 muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                 muxer!!.addTrack(format)
                 muxer!!.start()
                 muxer!!
            }, surface)
            
            Log.d(TAG, "Compression completed successfully")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Video compression failed", e)
            return false
        } finally {
            try {
                extractor?.release()
                decoder?.stop()
                decoder?.release()
                encoder?.stop()
                encoder?.release()
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing resources", e)
            }
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith(mimePrefix) == true) {
                return i
            }
        }
        return -1
    }

    private fun doVideoConversion(
        extractor: MediaExtractor, 
        decoder: MediaCodec, 
        encoder: MediaCodec, 
        muxerStartWrapper: (Int, MediaFormat) -> MediaMuxer,
        outputSurface: OutputSurface
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var muxer: MediaMuxer? = null
        var videoTrackIndex = -1
        var inputDone = false
        var outputDone = false
        
        while (!outputDone) {
            // Feed Decoder
            if (!inputDone) {
                val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputBufIndex >= 0) {
                    val inputBuf = decoder.getInputBuffer(inputBufIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuf, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // Drain Decoder / Feed Surface
            var decoderOutputAvailable = true
            while (decoderOutputAvailable) {
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    decoderOutputAvailable = false
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // unexpected
                } else if (outputBufferIndex >= 0) {
                    val doRender = bufferInfo.size != 0
                    decoder.releaseOutputBuffer(outputBufferIndex, doRender)
                    if (doRender) {
                        outputSurface.awaitNewImage()
                        outputSurface.drawImage(false)
                        outputSurface.setPresentationTime(bufferInfo.presentationTimeUs * 1000)
                        outputSurface.swapBuffers()
                    }
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoder.signalEndOfInputStream()
                    }
                }
            }

            // Drain Encoder / Feed Muxer
            var encoderOutputAvailable = true
            while (encoderOutputAvailable) {
                val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    encoderOutputAvailable = false
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = encoder.outputFormat
                    muxer = muxerStartWrapper(0, newFormat)
                    videoTrackIndex = 0 // Since we only have video, track 0 is fine
                } else if (encoderStatus >= 0) {
                    val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size != 0) {
                        if (muxer != null) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                    }

                    encoder.releaseOutputBuffer(encoderStatus, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }
        }
    }
}
