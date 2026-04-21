package ai.inmo.openclaw.constants

import java.util.Locale

object AppConstants {
    const val APP_NAME = "INMOClaw"
    const val VERSION = "1.8.4"
    const val PACKAGE_NAME = "ai.inmo.openclaw"

    /** Matches ANSI escape sequences (e.g. color codes in terminal output). */
    val ANSI_ESCAPE = Regex("\u001b\\[[0-9;]*[a-zA-Z]")

    const val AUTHOR_NAME = "Mithun Gowda B"
    const val AUTHOR_EMAIL = "mithungowda.b7411@gmail.com"
    const val GITHUB_URL = "https://github.com/mithun50/openclaw-termux"
    const val LICENSE = "MIT"

    const val GITHUB_API_LATEST_RELEASE =
        "https://api.github.com/repos/mithun50/openclaw-termux/releases/latest"

    // NextGenX
    const val ORG_NAME = "NextGenX"
    const val ORG_EMAIL = "nxgextra@gmail.com"
    const val INSTAGRAM_URL = "https://www.instagram.com/nexgenxplorer_nxg"
    const val YOUTUBE_URL = "https://youtube.com/@nexgenxplorer?si=UG-wBC8UIyeT4bbw"
    const val PLAY_STORE_URL =
        "https://play.google.com/store/apps/dev?id=8262374975871504599"

    const val GATEWAY_HOST = "127.0.0.1"
    const val GATEWAY_PORT = 18789
    const val GATEWAY_URL = "http://$GATEWAY_HOST:$GATEWAY_PORT"

    const val BOT_API_URL_PROD = "https://ai.bot.cjym.com"
    const val BOT_API_URL_TEST = "https://testai.bot.cjym.com"

    const val USER_AGREEMENT_URL =
        "https://cjym.feishu.cn/docx/SyBsdljyQoQmJJxLYTzchVI6nZB"
    const val PRIVACY_POLICY_URL =
        "https://cjym.feishu.cn/docx/KSUXdWlgcoKYbYxxtWLcsyXHnmh"

    // Ubuntu rootfs URLs
    private const val UBUNTU_ROOTFS_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-"
    const val ROOTFS_ARM64 = "${UBUNTU_ROOTFS_URL}arm64.tar.gz"
    const val ROOTFS_ARMHF = "${UBUNTU_ROOTFS_URL}armhf.tar.gz"
    const val ROOTFS_AMD64 = "${UBUNTU_ROOTFS_URL}amd64.tar.gz"

    // Node.js binary tarball
    const val NODE_VERSION = "22.13.1"
    private const val NODE_BASE_URL =
        "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-"

    // China mirror URLs
    private const val UBUNTU_ROOTFS_MIRROR_URL =
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-"
    private const val NODE_MIRROR_BASE_URL =
        "https://cdn.npmmirror.com/binaries/node/v$NODE_VERSION/node-v$NODE_VERSION-linux-"
    const val NPM_MIRROR_REGISTRY = "https://registry.npmmirror.com"
    const val APT_MIRROR_URL = "https://mirrors.tuna.tsinghua.edu.cn"

    const val DNS_DEFAULT = "nameserver 8.8.8.8\nnameserver 8.8.4.4\n"
    const val DNS_CHINA = "nameserver 223.5.5.5\nnameserver 223.6.6.6\n"

    // Prebundled rootfs asset (arm64 only)
    const val BUNDLED_ROOTFS_ASSET = "rootfs-arm64.tar.gz.bin"

    // Health check & reconnection
    const val HEALTH_CHECK_INTERVAL_MS = 5000L
    const val MAX_AUTO_RESTARTS = 5

    // Node constants
    const val WS_RECONNECT_BASE_MS = 350L
    const val WS_RECONNECT_MULTIPLIER = 1.7
    const val WS_RECONNECT_CAP_MS = 8000L
    const val NODE_ROLE = "node"
    const val PAIRING_TIMEOUT_MS = 300000L

    /** Detect if the user is likely in China based on device locale. */
    fun isChineseLocale(): Boolean {
        return try {
            Locale.getDefault().country == "CN"
        } catch (_: Exception) {
            false
        }
    }

    fun getNodeTarballUrl(arch: String, useMirror: Boolean = false): String {
        val base = if (useMirror) NODE_MIRROR_BASE_URL else NODE_BASE_URL
        return when (arch) {
            "aarch64" -> "${base}arm64.tar.xz"
            "arm" -> "${base}armv7l.tar.xz"
            "x86_64" -> "${base}x64.tar.xz"
            else -> "${base}arm64.tar.xz"
        }
    }

    fun getRootfsUrl(arch: String, useMirror: Boolean = false): String {
        val base = if (useMirror) UBUNTU_ROOTFS_MIRROR_URL else UBUNTU_ROOTFS_URL
        return when (arch) {
            "aarch64" -> "${base}arm64.tar.gz"
            "arm" -> "${base}armhf.tar.gz"
            "x86_64" -> "${base}amd64.tar.gz"
            else -> "${base}arm64.tar.gz"
        }
    }
}
