package com.meshcipher.presentation.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.meshcipher.domain.model.MediaMetadata
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
    val showMediaPicker by viewModel.showMediaPicker.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.sendPhoto(it) }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.sendVideo(it) }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startVoiceRecording()
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
                // Upload progress indicator
                if (sendingState is SendingState.Sending && uploadProgress > 0f && uploadProgress < 1f) {
                    @Suppress("DEPRECATION")
                    LinearProgressIndicator(
                        progress = uploadProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Voice recording bar
                if (isRecording) {
                    VoiceRecordingBar(
                        durationMs = recordingDuration,
                        onStop = { viewModel.stopVoiceRecording() },
                        onCancel = { viewModel.cancelVoiceRecording() }
                    )
                } else {
                    // Media picker panel
                    AnimatedVisibility(
                        visible = showMediaPicker,
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it })
                    ) {
                        MediaPickerPanel(
                            onPhotoClick = { photoPickerLauncher.launch("image/*") },
                            onVideoClick = { videoPickerLauncher.launch("video/*") },
                            onVoiceClick = {
                                viewModel.dismissMediaPicker()
                                if (ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    viewModel.startVoiceRecording()
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onDismiss = { viewModel.dismissMediaPicker() }
                        )
                    }

                    // Message input bar
                    MessageInputBar(
                        value = messageInput,
                        onValueChange = { viewModel.updateMessageInput(it) },
                        onSendClick = { viewModel.sendMessage() },
                        onAttachClick = { viewModel.toggleMediaPicker() },
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
                MessageBubble(message = message)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun MediaPickerPanel(
    onPhotoClick: () -> Unit,
    onVideoClick: () -> Unit,
    onVoiceClick: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Attach Media",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MediaPickerButton(
                    icon = Icons.Default.Image,
                    label = "Photo",
                    onClick = onPhotoClick
                )
                MediaPickerButton(
                    icon = Icons.Default.Videocam,
                    label = "Video",
                    onClick = onVideoClick
                )
                MediaPickerButton(
                    icon = Icons.Default.Mic,
                    label = "Voice",
                    onClick = onVoiceClick
                )
            }
        }
    }
}

@Composable
private fun MediaPickerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(icon, contentDescription = label)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun VoiceRecordingBar(
    durationMs: Long,
    onStop: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = MaterialTheme.colorScheme.error)
            }

            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            FilledIconButton(onClick = onStop) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "Stop recording"
                )
            }
        }
    }
}

@Composable
fun MessageBubble(message: Message) {
    val context = LocalContext.current

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
                    start = if (message.isMediaMessage && message.mediaType == MediaType.PHOTO) 4.dp else 12.dp,
                    end = if (message.isMediaMessage && message.mediaType == MediaType.PHOTO) 4.dp else 12.dp,
                    top = if (message.isMediaMessage && message.mediaType == MediaType.PHOTO) 4.dp else 12.dp,
                    bottom = 12.dp
                )
            ) {
                if (message.isMediaMessage) {
                    MediaContent(message, context)
                }

                if (!message.isMediaMessage || (message.mediaType != MediaType.PHOTO && message.mediaType != MediaType.VIDEO)) {
                    if (!message.isMediaMessage) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (message.isOwnMessage)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                        .padding(horizontal = if (message.isMediaMessage && message.mediaType == MediaType.PHOTO) 8.dp else 0.dp)
                )
            }
        }
    }
}

@Composable
private fun MediaContent(message: Message, context: android.content.Context) {
    val mediaId = message.mediaId ?: return
    val mediaType = message.mediaType ?: return
    val metadata = message.mediaMetadata

    val extension = mediaType.getFileExtension()
    val cacheFile = File(context.filesDir, "media_cache/$mediaId$extension")

    when (mediaType) {
        MediaType.PHOTO -> {
            if (cacheFile.exists()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(cacheFile)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Photo",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 250.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                PhotoPlaceholder(message)
            }
        }
        MediaType.VIDEO -> {
            if (cacheFile.exists()) {
                // Show thumbnail with play icon overlay
                val thumbFile = File(context.filesDir, "media_cache/${mediaId}_thumb.jpg")
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 250.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbFile.exists()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(thumbFile)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Video thumbnail",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {}
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Play video",
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                if (metadata?.durationMs != null) {
                    Text(
                        text = formatDuration(metadata.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.isOwnMessage)
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            } else {
                PhotoPlaceholder(message)
            }
        }
        MediaType.AUDIO -> {
            AudioMessageContent(message, metadata)
        }
        MediaType.FILE -> {
            FileIndicator(message, metadata)
        }
    }
}

@Composable
private fun PhotoPlaceholder(message: Message) {
    val metadata = message.mediaMetadata
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(4.dp)
    ) {
        Icon(
            imageVector = if (message.mediaType == MediaType.VIDEO)
                Icons.Default.Videocam else Icons.Default.Image,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (message.isOwnMessage)
                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = metadata?.let { formatFileSize(it.fileSize) } ?: message.content,
            style = MaterialTheme.typography.bodyMedium,
            color = if (message.isOwnMessage)
                MaterialTheme.colorScheme.onPrimary
            else
                MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AudioMessageContent(message: Message, metadata: MediaMetadata?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "Voice note",
            modifier = Modifier.size(24.dp),
            tint = if (message.isOwnMessage)
                MaterialTheme.colorScheme.onPrimary
            else
                MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        // Simple waveform placeholder bar
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = if (message.isOwnMessage)
                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        ) {}
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = metadata?.durationMs?.let { formatDuration(it) } ?: "0:00",
            style = MaterialTheme.typography.labelMedium,
            color = if (message.isOwnMessage)
                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun FileIndicator(message: Message, metadata: MediaMetadata?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.AttachFile,
            contentDescription = "File",
            modifier = Modifier.size(24.dp),
            tint = if (message.isOwnMessage)
                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = metadata?.fileName ?: "File",
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.isOwnMessage)
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (metadata != null) {
                Text(
                    text = formatFileSize(metadata.fileSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.isOwnMessage)
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onAttachClick: () -> Unit,
    isSending: Boolean = false
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
            IconButton(
                onClick = onAttachClick,
                enabled = !isSending
            ) {
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

            Spacer(modifier = Modifier.width(8.dp))

            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp).padding(8.dp),
                    strokeWidth = 2.dp
                )
            } else {
                FilledIconButton(
                    onClick = onSendClick,
                    enabled = value.isNotBlank()
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

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
