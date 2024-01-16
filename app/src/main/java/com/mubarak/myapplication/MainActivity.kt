package com.mubarak.myapplication

import android.app.Activity
import android.content.ContentValues
import android.content.ContentValues.TAG
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.mubarak.myapplication.databinding.ActivityMainBinding
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val SELECT_IMAGES_REQUEST = 1
    private val selectedImages = ArrayList<String>()

    private val CREATE_VIDEO_REQUEST = 2


    private lateinit var mediaCodec: MediaCodec
    private lateinit var mediaMuxer: MediaMuxer
    private lateinit var outputVideoFile: File
    private var trackIndex: Int = -1
    private var isMuxing: Boolean = false

    private val OUTPUT_VIDEO_MIME_TYPE = "video/avc"
    private val OUTPUT_VIDEO_WIDTH = 640
    private val OUTPUT_VIDEO_HEIGHT = 480
    private val OUTPUT_VIDEO_FRAME_RATE = 30
    private val OUTPUT_VIDEO_BIT_RATE = 2000000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request permissions if needed
        // Check and request the READ_EXTERNAL_STORAGE permission if not granted

        // Request permissions if needed
        // requestWriteStoragePermission()

        binding.btnOpenGallery.setOnClickListener {
            openGallery()
        }

        binding.createVideoButton.setOnClickListener {
            createVideo()
        }


    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        intent.type = "image/*"
        startActivityForResult(intent, SELECT_IMAGES_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SELECT_IMAGES_REQUEST && resultCode == Activity.RESULT_OK) {
            if (data?.clipData != null) {
                // Multiple images selected
                val count = data.clipData!!.itemCount
                for (i in 0 until count) {
                    val imageUri = data.clipData!!.getItemAt(i).uri
                    selectedImages.add(imageUri.toString())
                    Log.e(TAG, "onActivityResult: selectedImages 1  $imageUri")

                }
            } else if (data?.data != null) {
                // Single image selected
                val imageUri = data.data!!
                selectedImages.add(imageUri.toString())
                Log.e(TAG, "onActivityResult: selectedImages  2 $imageUri")

                Glide.with(this)
                    .load(imageUri)
                    .centerCrop()
                    .into(binding.myImageView);
            }

            // Optionally, you can display the selected images or perform further actions
        }

        if (requestCode == CREATE_VIDEO_REQUEST) {
            if (resultCode == Activity.RESULT_OK) {
                // Video creation was successful, you can proceed
                Log.e(TAG, "onActivityResult: Video creation was successful, you can proceed")
            } else {
                // Video creation was canceled or failed, handle accordingly
                Log.e(TAG, "onActivityResult: Video creation was failed")
            }
        }
    }

    private fun createVideo() {
        getOutputDirectory()
        outputVideoFile = createVideoFiles()

        // Initialize MediaCodec for video encoding
        val mediaFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            OUTPUT_VIDEO_WIDTH,
            OUTPUT_VIDEO_HEIGHT
        )
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_VIDEO_BIT_RATE)
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_VIDEO_FRAME_RATE)
        mediaFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)

        try {
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = mediaCodec.createInputSurface()
            val info = MediaCodec.BufferInfo()


            // Initialize MediaMuxer for multiplexing
            mediaMuxer = MediaMuxer(
                outputVideoFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            // Add a track to the MediaMuxer
            trackIndex = mediaMuxer.addTrack(mediaCodec.outputFormat)
            mediaMuxer.start()

            // Start encoding
            mediaCodec.start()
            isMuxing = true

            // Process each image and encode into the video
            for (imageUriString in selectedImages) {
                val imageBitmap = loadBitmapFromUri(imageUriString)

                Log.e(TAG, "createVideo: imageUriString $imageUriString")

                // Convert imageBitmap to YUV format
                val yuvData = convertBitmapToYUV(imageBitmap)

                val canvas = inputSurface.lockCanvas(null)
                canvas.drawBitmap(imageBitmap, 0f, 0f, null)
                inputSurface.unlockCanvasAndPost(canvas)

                // Write the image data to the inputBuffer
//                val inputBufferIndex = mediaCodec.dequeueOutputBuffer(-1)
                val inputBufferIndex = mediaCodec.dequeueOutputBuffer(info, -1)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex)
                    inputBuffer?.clear()

                    // Write your processed image data to inputBuffer
                    inputBuffer?.put(yuvData)

                    mediaCodec.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        yuvData.size,
                        0,
                        0
                    )
                }

                // Your code for getting output data from the encoder
                // Write encoded data to MediaMuxer
                val bufferInfo = MediaCodec.BufferInfo()
                val outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, -1)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null) {
                        mediaMuxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    mediaCodec.releaseOutputBuffer(outputBufferIndex, false)
                }
            }

            // Stop encoding and release resources
            mediaCodec.stop()
            mediaCodec.release()

            // Stop muxing and release MediaMuxer
            mediaMuxer.stop()
            mediaMuxer.release()

        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun createVideoFiles(): File {

        val outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val outputPath = File(outputDir, "output_${System.currentTimeMillis()}.mp4")

        if (outputDir != null) {
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
        }
        // Notify MediaStore about the new file
        MediaScannerConnection.scanFile(this, arrayOf(outputPath.absolutePath), null) { path, uri ->
            Log.d(TAG, "MediaScanner scanned path: $path")
            Log.d(TAG, "MediaScanner scanned uri: $uri")
        }
        return outputPath
    }

    private fun loadBitmapFromUri(uriString: String): Bitmap {
        // Implement your logic to load a Bitmap from the given URI
        // You might use Glide or another image loading library
        // For simplicity, we'll assume the URI is a content URI
        val uri = Uri.parse(uriString)
        return MediaStore.Images.Media.getBitmap(contentResolver, uri)
    }

    /*private fun requestWriteStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "output.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            }

            val uri: Uri? =
                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { grantUriPermission(packageName, it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }

            val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
            }

            startActivityForResult(intent, CREATE_VIDEO_REQUEST)
        }
    }
*/

    private fun convertBitmapToYUV(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height

        // Calculate the number of pixels
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert ARGB to YUV
        val yuv = ByteArray(width * height * 3 / 2)
        encodeYUV420SP(yuv, pixels, width, height)

        return yuv
    }

    private fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height

        var yIndex = 0
        var uvIndex = frameSize

        var R: Int
        var G: Int
        var B: Int
        var Y: Int
        var U: Int
        var V: Int
        var index = 0

        for (j in 0 until height) {
            for (i in 0 until width) {

                R = argb[index] and 0xff0000 shr 16
                G = argb[index] and 0xff00 shr 8
                B = argb[index] and 0xff

                // RGB to YUV
                Y = (66 * R + 129 * G + 25 * B + 128 shr 8) + 16
                U = (-38 * R - 74 * G + 112 * B + 128 shr 8) + 128
                V = (112 * R - 94 * G - 18 * B + 128 shr 8) + 128

                yuv420sp[yIndex++] = clip(Y)
                if (j % 2 == 0 && index % 2 == 0) {
                    yuv420sp[uvIndex++] = clip(U)
                    yuv420sp[uvIndex++] = clip(V)
                }

                index++
            }
        }
    }

    private fun clip(value: Int): Byte {
        return if (value < 0) 0.toByte() else if (value > 255) 255.toByte() else value.toByte()
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }
}
