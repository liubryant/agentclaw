package ai.inmo.openclaw.domain.model

data class OptionalPackage(
    val id: String,
    val name: String,
    val description: String,
    val iconResName: String,
    val color: Int,
    val installCommand: String,
    val uninstallCommand: String,
    val checkPath: String,
    val estimatedSize: String,
    val completionSentinel: String
) {
    val uninstallSentinel: String
        get() = completionSentinel.replace("INSTALL", "UNINSTALL")

    companion object {
        val GO = OptionalPackage(
            id = "go",
            name = "Go (Golang)",
            description = "Go programming language compiler and tools",
            iconResName = "ic_integration_instructions",
            color = 0xFF00BCD4.toInt(),
            installCommand = "set -e; " +
                    "echo \">>> Installing Go via apt...\"; " +
                    "apt-get update -qq && apt-get install -y golang; " +
                    "go version; " +
                    "echo \">>> GO_INSTALL_COMPLETE\"",
            uninstallCommand = "set -e; " +
                    "echo \">>> Removing Go...\"; " +
                    "apt-get remove -y golang golang-go && apt-get autoremove -y; " +
                    "echo \">>> GO_UNINSTALL_COMPLETE\"",
            checkPath = "usr/bin/go",
            estimatedSize = "~150 MB",
            completionSentinel = "GO_INSTALL_COMPLETE"
        )

        val BREW = OptionalPackage(
            id = "brew",
            name = "Homebrew",
            description = "The missing package manager for Linux",
            iconResName = "ic_science",
            color = 0xFFFFC107.toInt(),
            installCommand = "set -e; " +
                    "echo \">>> Installing Homebrew (this may take a while)...\"; " +
                    "touch /.dockerenv; " +
                    "apt-get update -qq && apt-get install -y -qq " +
                    "build-essential procps curl file git; " +
                    "NONINTERACTIVE=1 /bin/bash -c \"\$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\"; " +
                    "grep -q 'linuxbrew' /root/.bashrc 2>/dev/null || { " +
                    "echo 'eval \"\$(//home/linuxbrew/.linuxbrew/bin/brew shellenv)\"' >> /root/.bashrc; " +
                    "}; " +
                    "eval \"\$(/home/linuxbrew/.linuxbrew/bin/brew shellenv)\"; " +
                    "brew --version; " +
                    "echo \">>> BREW_INSTALL_COMPLETE\"",
            uninstallCommand = "set -e; " +
                    "echo \">>> Removing Homebrew...\"; " +
                    "touch /.dockerenv; " +
                    "NONINTERACTIVE=1 /bin/bash -c \"\$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/uninstall.sh)\" || true; " +
                    "rm -rf /home/linuxbrew/.linuxbrew; " +
                    "sed -i '/linuxbrew/d' /root/.bashrc; " +
                    "echo \">>> BREW_UNINSTALL_COMPLETE\"",
            checkPath = "home/linuxbrew/.linuxbrew/bin/brew",
            estimatedSize = "~500 MB",
            completionSentinel = "BREW_INSTALL_COMPLETE"
        )

        val SSH = OptionalPackage(
            id = "ssh",
            name = "OpenSSH",
            description = "SSH client and server for secure remote access",
            iconResName = "ic_vpn_key",
            color = 0xFF009688.toInt(),
            installCommand = "set -e; " +
                    "echo \">>> Installing OpenSSH...\"; " +
                    "apt-get update -qq && apt-get install -y openssh-client openssh-server; " +
                    "ssh -V; " +
                    "echo \">>> SSH_INSTALL_COMPLETE\"",
            uninstallCommand = "set -e; " +
                    "echo \">>> Removing OpenSSH...\"; " +
                    "apt-get remove -y openssh-client openssh-server && apt-get autoremove -y; " +
                    "echo \">>> SSH_UNINSTALL_COMPLETE\"",
            checkPath = "usr/bin/ssh",
            estimatedSize = "~10 MB",
            completionSentinel = "SSH_INSTALL_COMPLETE"
        )

        val ALL = listOf(GO, BREW, SSH)
    }
}
