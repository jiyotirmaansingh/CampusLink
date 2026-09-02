CampusLink

An Android-based decentralized mesh networking application that enables offline, campus-wide communication and file sharing. Engineered to bypass internet dependency, CampusLink utilizes Google Nearby Connections and an AI-powered routing engine to establish a resilient peer-to-peer (P2P) network topology.
System Architecture & Technical Highlights

    Intelligent Multi-Hop Routing: Integrates a custom TensorFlow Lite model for edge inference, dynamically selecting the optimal relay node based on real-time peer battery life, system uptime, and signal stability.

    Decentralized P2P Infrastructure: Leverages the Google Nearby Connections API to establish a low-latency, offline mesh topology.

    Fault Tolerance & Loop Prevention: Implements deterministic data delivery and prevents infinite network routing loops through strict unique transfer ID tracking across all mesh nodes.

    Frictionless State Management: Utilizes role-based architecture to handle automated handshake protocols and auto-accept configurations for administrative broadcasts.

Core Modules

Admin Command Center

    Instantly broadcast campus-wide notices and files.

    Auto-accept protocols ensure seamless distribution to all connected nodes without requiring manual receiver intervention.

Student Dashboard

    Notice Board: Automatically fetches and displays authoritative broadcasts from the Admin Command Center.

    Peer Zone: Enables secure, direct local file exchange between individual student nodes.

Tech Stack

    Platform: Android (Kotlin / Java)

    Machine Learning / Edge AI: TensorFlow Lite

    Networking: Google Nearby Connections API

    Architecture: Multi-hop Mesh Topology, Role-Based Access Control

Installation & Setup

    Clone the repository:
    Bash

    git clone https://github.com/jiyotirmaansingh/CampusLink.git

    Open the project in Android Studio.

    Sync the Gradle files to download the required dependencies, including the TensorFlow Lite libraries.

    Build and deploy the APK to a physical Android device. (Note: The Google Nearby Connections API requires physical hardware for Bluetooth/Wi-Fi Direct functionality and will not operate fully on an emulator).

Usage

    Role Selection: Upon initial launch, configure the node as either an Admin or a Student.

    Permissions: Grant the necessary Location, Bluetooth, and Nearby Devices permissions required for hardware-level peer discovery.

    Admin Operation: Select a payload (text notice or file) and initiate a broadcast. The routing engine handles peer discovery and multi-hop distribution automatically.

    Student Operation: Keep the app open to passively receive administrative broadcasts on the Notice Board, or navigate to the Peer Zone to actively discover and exchange files with nearby student nodes.
