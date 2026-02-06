package com.meshcipher.presentation.chat

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.meshcipher.domain.model.MediaAttachment
import com.meshcipher.domain.model.MediaType
import com.meshcipher.domain.model.Message
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBackClick: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val contact by viewModel.contact.collectAsState()
    val messageInput by viewModel.messageInput.collectAsState()
    val sendingState by viewModel.sendingState.collectAsState()
    val mediaPickerVisible by viewModel.mediaPickerVisible.collectAsState()
    val mediaSendingProgress by viewModel.mediaSendingProgress.collectAsState()
    val isRecordingVoice by viewModel.voiceRecorder.isRecording.collectAsState()
    val recordingDuration by viewModel.voiceRecorder.recordingDurationMs.collectAsState()

    val context = LocalContext.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    var viewingMediaPath by remember { mutableStateOf<String?>(null) }
    var viewingMediaIsVideo by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.sendMediaMessage(context, it, MediaType.IMAGE) }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.sendMediaMessage(context, it, MediaType.VIDEO) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val file = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            viewModel.sendMediaMessage(context, Uri.fromFile(file), MediaType.IMAGE)
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startVoiceRecording(context)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
            viewModel.markAsRead()
        }
    }

    LaunchedEffect(sendingState) {
        if (sendingState is SendingState.Error) {
            val error = (sendingState as SendingState.Error).message
            snackbarHostState.showSnackbar(
                message = "Send failed: $error",
                duration = SnackbarDuration.Long
            )
            viewModel.clearSendingState()
        }
    }

    // Media viewer dialog
    viewingMediaPath?.let { path ->
        MediaViewerDialog(
            filePath = path,
            isVideo = viewingMediaIsVideo,
            onDismiss = { viewingMediaPath = null }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(contact?.displayName ?: "Loading...") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Column {
                mediaSendingProgress?.let { progress ->
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (isRecordingVoice) {
                    VoiceRecordBar(
                        durationMs = recordingDuration,
                        onCancel = { viewModel.cancelVoiceRecording() },
                        onStop = { viewModel.sendVoiceMessage(context) }
                    )
                } else {
                    MessageInputBar(
                        value = messageInput,
                        onValueChange = { viewModel.updateMessageInput(it) },
                        onSendClick = { viewModel.sendMessage() },
                        onAttachClick = { viewModel.toggleMediaPicker() },
                        onMicClick = {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        isSending = sendingState is SendingState.Sending
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp),
            state = listState,
            reverseLayout = true
        ) {
            items(messages) { message ->
                MessageBubble(
                    message = message,
                    viewModel = viewModel,
                    onMediaClick = { path, isVideo ->
                        viewingMediaPath = path
                        viewingMediaIsVideo = isVideo
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    // Media picker bottom sheet
    if (mediaPickerVisible) {
        MediaPickerSheet(
            onDismiss = { viewModel.dismissMediaPicker() },
            onCameraClick = {
                viewModel.dismissMediaPicker()
                cameraLauncher.launch(null)
            },
            onGalleryClick = {
                viewModel.dismissMediaPicker()
                imagePickerLauncher.launch("image/*")
            },
            onVideoClick = {
                viewModel.dismissMediaPicker()
                videoPickerLauncher.launch("video/*")
            }
        )
    }
}

@Composable
fun MessageBubble(
    message: Message,
    viewModel: ChatViewModel,
    onMediaClick: (String, Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isOwnMessage)
            Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isOwnMessage) 16.dp else 4.dp,
                bottomEnd = if (message.isOwnMessage) 4.dp else 16.dp
            ),
            color = if (message.isOwnMessage)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(
                modifier = Modifier.padding(
                    if (message.mediaAttachment != null) 4.dp else 12.dp
                )
            ) {
                val attachment = message.mediaAttachment
                if (attachment != null) {
                    MediaMessageContent(
                        attachment = attachment,
                        isOwnMessage = message.isOwnMessage,
                        viewModel = viewModel,
                        onMediaClick = onMediaClick
                    )
                } else {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (message.isOwnMessage)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = formatTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.isOwnMessage)
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(horizontal = if (message.mediaAttachment != null) 8.dp else 0.dp)
                )
            }
        }
    }
}

@Composable
private fun MediaMessageContent(
    attachment: MediaAttachment,
    isOwnMessage: Boolean,
    viewModel: ChatViewModel,
    onMediaClick: (String, Boolean) -> Unit
) {
    val localPath = attachment.localPath

    when (attachment.mediaType) {
        MediaType.IMAGE -> {
            if (localPath != null && File(localPath).exists()) {
                AsyncImage(
                    model = File(localPath),
                    contentDescription = "Image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 250.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onMediaClick(localPath, false) },
                    contentScale = ContentScale.Crop
                )
            } else {
                MediaPlaceholder(
                    icon = Icons.Default.Image,
                    label = "Image",
                    isOwnMessage = isOwnMessage
                )
            }
        }
        MediaType.VIDEO -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 250.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        if (localPath != null && File(localPath).exists()) {
                            onMediaClick(localPath, true)
                        }
                    }
            ) {
                if (localPath != null && File(localPath).exists()) {
                    AsyncImage(
                        model = File(localPath),
                        contentDescription = "Video",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = "Play",
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                attachment.durationMs?.let { duration ->
                    Text(
                        text = formatDuration(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                    )
                }
            }
        }
        MediaType.VOICE -> {
            VoiceMessageBubble(
                attachment = attachment,
                isOwnMessage = isOwnMessage,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun VoiceMessageBubble(
    attachment: MediaAttachment,
    isOwnMessage: Boolean,
    viewModel: ChatViewModel
) {
    val playingMediaId by viewModel.voicePlayer.playingMediaId.collectAsState()
    val isPlaying by viewModel.voicePlayer.isPlaying.collectAsState()
    val playbackProgress by viewModel.voicePlayer.playbackProgress.collectAsState()

    val isThisPlaying = playingMediaId == attachment.mediaId && isPlaying

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                val path = attachment.localPath
                if (path != null && File(path).exists()) {
                    viewModel.voicePlayer.play(path, attachment.mediaId)
                }
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isThisPlaying) "Pause" else "Play",
                tint = if (isOwnMessage)
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        val progress = if (playingMediaId == attachment.mediaId) playbackProgress else 0f
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .weight(1f)
                .height(4.dp),
            color = if (isOwnMessage)
                MaterialTheme.colorScheme.onPrimary
            else
                MaterialTheme.colorScheme.primary,
            trackColor = if (isOwnMessage)
                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = formatDuration(attachment.durationMs ?: 0),
            style = MaterialTheme.typography.labelSmall,
            color = if (isOwnMessage)
                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun MediaPlaceholder(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isOwnMessage: Boolean
) {
    Row(
        modifier = Modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (isOwnMessage)
                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isOwnMessage)
                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun VoiceRecordBar(
    durationMs: Long,
    onCancel: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Cancel recording",
                    tint = MaterialTheme.colorScheme.error
                )
            }

            Icon(
                Icons.Default.FiberManualRecord,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(12.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )

            FilledIconButton(onClick = onStop) {
                Icon(Icons.Default.Send, contentDescription = "Send voice note")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPickerSheet(
    onDismiss: () -> Unit,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onVideoClick: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Share Media",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            ListItem(
                headlineContent = { Text("Camera") },
                leadingContent = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onCameraClick)
            )

            ListItem(
                headlineContent = { Text("Photo from Gallery") },
                leadingContent = { Icon(Icons.Default.Image, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onGalleryClick)
            )

            ListItem(
                headlineContent = { Text("Video from Gallery") },
                leadingContent = { Icon(Icons.Default.Videocam, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onVideoClick)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onAttachClick: () -> Unit,
    onMicClick: () -> Unit,
    isSending: Boolean
) {
    Surface(
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(onClick = onAttachClick) {
                Icon(Icons.Default.AttachFile, contentDescription = "Attach media")
            }

            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                enabled = !isSending
            )

            Spacer(modifier = Modifier.width(4.dp))

            if (value.isBlank()) {
                FilledIconButton(
                    onClick = onMicClick,
                    enabled = !isSending
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Voice note")
                }
            } else {
                FilledIconButton(
                    onClick = onSendClick,
                    enabled = value.isNotBlank() && !isSending
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
