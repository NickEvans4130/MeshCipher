# MeshCipher Documentation

Welcome to the detailed documentation for **MeshCipher**, a privacy-focused encrypted messaging app for Android.

## Table of Contents

1. [Introduction](#introduction)
2. [Architecture](architecture.md)
3. [Tech Stack](tech_stack.md)
4. [Security & Cryptography](security.md)
5. [Networking & Transport](networking.md)
6. [Media Handling & IPFS](media_handling.md)
7. [Data Storage](data_storage.md)
8. [User Guide](user_guide.md)

## Introduction

MeshCipher is designed to provide secure, off-the-grid communication capabilities using a multi-layered transport system. It prioritizes user privacy, data sovereignty, and resilience against censorship and network outages.

### Key Features

*   **Offline First**: Bluetooth Mesh networking allows communication without any internet infrastructure.
*   **Privacy Preserving**: End-to-end encryption using the Signal Protocol.
*   **Anonymity**: Optional TOR routing for internet-based metadata protection.
*   **Decentralized Media**: IPFS-based encrypted media storage.
*   **Hardware Security**: Keys are bound to the device's secure hardware (Trusted Execution Environment).

## Getting Started

If you are a developer looking to contribute, please start by reading the [Architecture](architecture.md) and [Tech Stack](tech_stack.md) guides to understand the system's design.

For specific implementation details on the most complex parts of the system, refer to:
*   [Networking](networking.md) for how the Bluetooth Mesh and TOR integration works.
*   [Security](security.md) for the cryptographic identity and encryption implementation.
