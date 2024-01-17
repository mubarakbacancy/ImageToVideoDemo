package com.mubarak.myapplication

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.widget.Button
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mubarak.imagetovideo.EncodeListener
import com.mubarak.imagetovideo.ImageToVideoConverter
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val imagePaths: MutableList<String> = mutableListOf()

    // Register the result launcher for picking multiple images
    private val pickImagesLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {

                result.data?.let { handleImageSelectionResult(it) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val createVideoButton: Button = findViewById(R.id.createVideoButton)

        createVideoButton.setOnClickListener {
            // Check and request storage permission before creating the video
            if (checkWriteStoragePermission()) {
                pickImages()
//                createVideo()
            } else {
                requestWriteStoragePermission()
            }
        }
    }

    private fun pickImages() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        pickImagesLauncher.launch(intent)
    }

    private fun handleImageSelectionResult(data: Intent) {
        val selectedImages: MutableList<String> = mutableListOf()

        // Handle single image selection
        data.data?.let { uri ->
            val decodedUriString = Uri.decode(uri.toString())
            val decodedUri = Uri.parse(decodedUriString)
            Log.e(TAG, "handleImageSelectionResult: decodedUri $decodedUri")

            getImagePathFromUri(this, decodedUri)?.let { imagePath ->
                Log.e(TAG, "handleImageSelectionResult: imagePath uri : $imagePath")
                selectedImages.add(imagePath)
            }
        }

        // Handle multiple image selection
        val clipData = data.clipData
        clipData?.let {
            for (i in 0 until it.itemCount) {
                val uri = it.getItemAt(i).uri

                val decodedUriString = Uri.decode(uri.toString())
                val decodedUri = Uri.parse(decodedUriString)
                Log.e(TAG, "handleImageSelectionResult: decodedUri $decodedUri")

                getImagePathFromUri(this, decodedUri)?.let { imagePath ->
                    Log.e(TAG, "handleImageSelectionResult: imagePath uri clipData : $imagePath")
                    selectedImages.add(imagePath)
                }
            }
        }

        // Process selected images and create video
        if (selectedImages.isNotEmpty()) {
            imagePaths.clear()
            imagePaths.addAll(selectedImages)
            createVideo()
        } else {
            Log.e(TAG, "handleImageSelectionResult: selectedImages $selectedImages")

        }
    }

    // Check if WRITE_EXTERNAL_STORAGE permission is granted
    private fun checkWriteStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Request WRITE_EXTERNAL_STORAGE permission
    private fun requestWriteStoragePermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            WRITE_STORAGE_PERMISSION_REQUEST_CODE
        )
    }

    // Create a video file and notify MediaStore about the new file
    private fun createVideoFiles(): String {
        val outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val outputPath = File(outputDir, "output_${System.currentTimeMillis()}.mp4")

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        // Notify MediaStore about the new file
        MediaScannerConnection.scanFile(this, arrayOf(outputPath.absolutePath), null) { path, uri ->
            Log.d(TAG, "MediaScanner scanned path: $path")
            Log.d(TAG, "MediaScanner scanned uri: $uri")
        }

        return outputPath.absolutePath
    }

    // Check if external storage is writable
    private fun isExternalStorageWritable(): Boolean {
        val state = Environment.getExternalStorageState()
        return Environment.MEDIA_MOUNTED == state
    }

    // Create a video using ImageToVideoConverter
    private fun createVideo() {
        if (isExternalStorageWritable()) {
            // Get the output video path and example image paths
            val outputVideoPath = createVideoFiles()

            /* Log.e(TAG, "createVideo: imagePaths $imagePaths")
             val imageUri = Uri.parse("content://media/external/images/media/19")
             val imagePath = getImagePathFromUri(this, imageUri)*/

            // Log the image path for debugging
            Log.e(TAG, "createVideo: imagePath :: $imagePaths")

            val imageToVideo = ImageToVideoConverter(
                outputPath = outputVideoPath,
                inputImagePath = imagePaths[0].toString(),
                size = Size(720, 720),
                duration = TimeUnit.SECONDS.toMicros(10),
                listener = object : EncodeListener {
                    override fun onProgress(progress: Float) {
                        Log.d("progress", "progress = ${(progress * 100).toInt()}")
                        runOnUiThread {
                            // Toast.makeText(this@MainActivity, "${(progress * 100).toInt()}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onCompleted() {
                        Log.e(TAG, "onCompleted: Video created")
                    }

                    override fun onFailed(exception: Exception) {
                        Log.e(TAG, "onFailed: ${exception.message}")
                    }
                }
            )
            imageToVideo?.start()
        } else {
            Log.e(TAG, "createVideo: Failed to create video due to external storage not writable")
        }
    }

    fun convertFloatToIntInRange(value: Float): Int {
        // Ensure the value is within the range 0.0 to 100.0
        val clampedValue = value.coerceIn(0.0f, 100.0f)

        // Convert the clamped value to an integer
        val intValue = (clampedValue * 100).toInt()

        return intValue
    }

    // Get the file path from a content URI
    private fun getImagePathFromUri(context: Context, uri: Uri): String? {
        var imagePath: String? = null

        if (DocumentsContract.isDocumentUri(context, uri)) {
            // Document URI
            val documentId = DocumentsContract.getDocumentId(uri)
            val split = documentId.split(":").toTypedArray()
            val type = split[0]

            if ("primary".equals(type, ignoreCase = true)) {
                // Handle primary storage
                imagePath = "${Environment.getExternalStorageDirectory()}/${split[1]}"
            } else {
                // Handle non-primary storage
                val contentUri = when (type) {
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> null
                }

                val selection = "_id=?"
                val selectionArgs = arrayOf(split[1])
                contentUri?.let {
                    imagePath = getDataColumn(context, it, selection, selectionArgs)
                }
            }
        } else if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
            // Content URI obtained through SAF
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val columnIndex = it.getColumnIndex(MediaStore.Images.ImageColumns.DATA)
                    if (columnIndex != -1) {
                        imagePath = it.getString(columnIndex)
                    }
                }
            }
        } else if (ContentResolver.SCHEME_FILE == uri.scheme) {
            // File URI
            imagePath = uri.path
        }

        return imagePath
    }

    // Check if the authority of the URI is for external storage documents
    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    // Check if the authority of the URI is for downloads documents
    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    // Check if the authority of the URI is for media documents
    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    // Get the data column for a given URI
    private fun getDataColumn(
        context: Context,
        uri: Uri?,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)

        try {
            cursor = uri?.let {
                context.contentResolver.query(
                    it,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )
            }
            cursor?.let {
                if (it.moveToFirst()) {
                    val columnIndex = it.getColumnIndexOrThrow(column)
                    return it.getString(columnIndex)
                }
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val WRITE_STORAGE_PERMISSION_REQUEST_CODE = 101
    }
}