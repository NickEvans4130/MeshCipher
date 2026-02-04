# User Guide

## Onboarding
When you first launch MeshCipher, the app generates a unique cryptographic identity for you.
1.  **Welcome Screen**: Explains the security features.
2.  **Identity Generation**: The app generates keys in your device's secure hardware. This may take a few seconds.
3.  **Profile**: Set your Display Name. Note that this name is only shared with contacts you explicitly add.
4.  **Biometrics**: You will be asked to enable Fingerprint or Face Unlock to secure the app.

## Adding Contacts
MeshCipher does not use phone numbers or central directories. You must exchange keys with contacts to talk to them.

### Via QR Code (Recommended)
1.  Tap the **+** (Add) button on the main screen.
2.  Select **Scan QR Code**.
3.  Have your friend open their profile by tapping their avatar and showing their QR code.
4.  Scan the code. The app will perform a cryptographic handshake to verify their identity.

### Via Copy/Paste
1.  In your profile, tap **Copy ID**.
2.  Send this ID to your friend via another secure channel.
3.  They can paste this ID into the "Add Contact" field.
4.  *Note: This is less secure than QR scanning as it does not verify the physical presence of the device.*

## Sending Messages

### Text Messages
*   Type and send.
*   **Review Status**:
    *   🕒 Clock: Sending...
    *   ✔ Check: Sent to Relay/Mesh.
    *   ✔✔ Double Check: Delivered to recipient.
    *   Blue ✔✔: Read by recipient.

### Media (Photos/Videos)
*   Tap the **Attachment** icon.
*   Select a photo or video.
*   The media will be encrypted and uploaded. Large videos may take time to process.

## Using Offline Mode (Bluetooth Mesh)
If the internet goes down:
1.  Ensure **Bluetooth** is enabled on your device.
2.  Move within range (approx. 30-100 feet) of other MeshCipher users.
3.  Messages will automatically hop from device to device to reach the recipient.
4.  You can view the mesh status in **Settings > Network Status**.
