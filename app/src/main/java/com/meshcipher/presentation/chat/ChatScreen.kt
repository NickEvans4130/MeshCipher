package com.meshcipher.presentation.chat

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.meshcipher.domain.model.MediaAttachment
import com.meshcipher.domain.model.MediaType
import com.meshcipher.domain.model.Message
import com.meshcipher.presentation.theme.*
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
    val activeTransportLabel by viewModel.activeTransportLabel.collectAsState()

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

    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraImageUri?.let { uri ->
                viewModel.sendMediaMessage(context, uri, MediaType.IMAGE)
            }
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
                title = {
                    Column {
                        Text(
                            text = contact?.displayName ?: "Loading...",
                            fontFamily = InterFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            color = TextPrimary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = SecureGreen,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = "end-to-end encrypted",
                                fontFamily = RobotoMonoFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 10.sp,
                                color = TextTertiary
                            )
                            Text(
                                text = "·",
                                fontFamily = RobotoMonoFontFamily,
                                fontSize = 10.sp,
                                color = TextTertiary
                            )
                            Text(
                                text = activeTransportLabel,
                                fontFamily = RobotoMonoFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 10.sp,
                                color = SecureGreen
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TacticalBackground,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary
                )
            )
        },
        bottomBar = {
            Column {
                mediaSendingProgress?.let { progress ->
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth(),
                        color = SecureGreen,
                        trackColor = TacticalElevated
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
        },
        containerColor = TacticalBackground
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

    if (mediaPickerVisible) {
        MediaPickerSheet(
            onDismiss = { viewModel.dismissMediaPicker() },
            onCameraClick = {
                viewModel.dismissMediaPicker()
                val photoFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
                cameraImageUri = uri
                cameraLauncher.launch(uri)
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
            color = if (message.isOwnMessage) SecureGreen else TacticalSurface,
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
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp,
                        color = if (message.isOwnMessage) OnSecureGreen else TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = formatTime(message.timestamp),
                    fontFamily = RobotoMonoFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.sp,
                    color = if (message.isOwnMessage)
                        OnSecureGreen.copy(alpha = 0.6f)
                    else
                        TextMono,
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
    when (attachment.mediaType) {
        MediaType.IMAGE -> {
            // Decrypt image to bitmap in memory
            val bitmap = remember(attachment.mediaId) {
                val bytes = viewModel.decryptMediaBytes(attachment.mediaId, MediaType.IMAGE)
                if (bytes != null) {
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else null
            }

            if (bitmap != null) {
                AsyncImage(
                    model = bitmap,
                    contentDescription = "Image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 250.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            // Decrypt to temp file for full-screen viewer
                            val tempFile = viewModel.decryptMediaToTempFile(attachment.mediaId, MediaType.IMAGE)
                            if (tempFile != null) {
                                onMediaClick(tempFile.absolutePath, false)
                            }
                        },
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
                        val tempFile = viewModel.decryptMediaToTempFile(attachment.mediaId, MediaType.VIDEO)
                        if (tempFile != null) {
                            onMediaClick(tempFile.absolutePath, true)
                        }
                    }
            ) {
                // Decrypt thumbnail for video preview
                val bitmap = remember(attachment.mediaId) {
                    val bytes = viewModel.decryptMediaBytes(attachment.mediaId, MediaType.VIDEO)
                    if (bytes != null) {
                        try {
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (_: Exception) { null }
                    } else null
                }
                if (bitmap != null) {
                    AsyncImage(
                        model = bitmap,
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
                    tint = TextPrimary.copy(alpha = 0.8f)
                )
                attachment.durationMs?.let { duration ->
                    Text(
                        text = formatDuration(duration),
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 11.sp,
                        color = TextPrimary,
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
    val fgColor = if (isOwnMessage) OnSecureGreen else TextPrimary
    val trackColor = if (isOwnMessage) OnSecureGreen.copy(alpha = 0.3f) else SecureGreen.copy(alpha = 0.3f)
    val progressColor = if (isOwnMessage) OnSecureGreen else SecureGreen

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                val tempFile = viewModel.decryptMediaToTempFile(attachment.mediaId, MediaType.VOICE)
                if (tempFile != null) {
                    viewModel.voicePlayer.play(tempFile.absolutePath, attachment.mediaId)
                }
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isThisPlaying) "Pause" else "Play",
                tint = fgColor
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        val progress = if (playingMediaId == attachment.mediaId) playbackProgress else 0f
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .weight(1f)
                .height(4.dp),
            color = progressColor,
            trackColor = trackColor
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = formatDuration(attachment.durationMs ?: 0),
            fontFamily = RobotoMonoFontFamily,
            fontSize = 11.sp,
            color = fgColor.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun MediaPlaceholder(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isOwnMessage: Boolean
) {
    val color = if (isOwnMessage) OnSecureGreen.copy(alpha = 0.7f) else TextSecondary

    Row(
        modifier = Modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = color)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            fontFamily = InterFontFamily,
            fontSize = 14.sp,
            color = color
        )
    }
}

@Composable
fun VoiceRecordBar(
    durationMs: Long,
    onCancel: () -> Unit,
    onStop: () -> Unit
) {
    Surface(color = TacticalSurface) {
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
                    tint = StatusError
                )
            }

            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(StatusError, CircleShape)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = formatDuration(durationMs),
                fontFamily = RobotoMonoFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )

            FilledIconButton(
                onClick = onStop,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = SecureGreen,
                    contentColor = OnSecureGreen
                )
            ) {
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TacticalSurface,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Share Media",
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            ListItem(
                headlineContent = {
                    Text("Camera", fontFamily = InterFontFamily, color = TextPrimary)
                },
                leadingContent = {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = SecureGreen)
                },
                modifier = Modifier.clickable(onClick = onCameraClick),
                colors = ListItemDefaults.colors(containerColor = TacticalSurface)
            )

            ListItem(
                headlineContent = {
                    Text("Photo from Gallery", fontFamily = InterFontFamily, color = TextPrimary)
                },
                leadingContent = {
                    Icon(Icons.Default.Image, contentDescription = null, tint = SecureGreen)
                },
                modifier = Modifier.clickable(onClick = onGalleryClick),
                colors = ListItemDefaults.colors(containerColor = TacticalSurface)
            )

            ListItem(
                headlineContent = {
                    Text("Video from Gallery", fontFamily = InterFontFamily, color = TextPrimary)
                },
                leadingContent = {
                    Icon(Icons.Default.Videocam, contentDescription = null, tint = SecureGreen)
                },
                modifier = Modifier.clickable(onClick = onVideoClick),
                colors = ListItemDefaults.colors(containerColor = TacticalSurface)
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
    Surface(color = TacticalSurface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(onClick = onAttachClick) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "Attach media",
                    tint = TextSecondary
                )
            }

            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "Message",
                        fontFamily = InterFontFamily,
                        color = TextTertiary
                    )
                },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                enabled = !isSending,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = SecureGreen,
                    unfocusedBorderColor = DividerMedium,
                    cursorColor = SecureGreen,
                    focusedContainerColor = TacticalElevated,
                    unfocusedContainerColor = TacticalElevated
                )
            )

            Spacer(modifier = Modifier.width(4.dp))

            if (value.isBlank()) {
                FilledIconButton(
                    onClick = onMicClick,
                    enabled = !isSending,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SecureGreen,
                        contentColor = OnSecureGreen
                    )
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Voice note")
                }
            } else {
                FilledIconButton(
                    onClick = onSendClick,
                    enabled = value.isNotBlank() && !isSending,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SecureGreen,
                        contentColor = OnSecureGreen
                    )
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
