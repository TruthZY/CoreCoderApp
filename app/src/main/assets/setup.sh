#!/bin/sh
# CoreCoder Ubuntu rootfs setup script
# Runs inside proot environment to configure the rootfs

set -e

echo "[CoreCoder] Starting rootfs setup..."

# 1. Create fake /proc entries (Android restricts real /proc access in proot)
echo "[CoreCoder] Creating fake /proc entries..."
mkdir -p /proc
if [ ! -f /proc/stat ]; then
    cat > /proc/stat << 'EOF'
cpu  0 0 0 0 0 0 0 0 0 0
cpu0 0 0 0 0 0 0 0 0 0
cpu1 0 0 0 0 0 0 0 0 0 0
cpu2 0 0 0 0 0 0 0 0 0 0
cpu3 0 0 0 0 0 0 0 0 0 0
intr 0
ctxt 0
btime 0
processes 0
procs_running 1
procs_blocked 0
softirq 0
EOF
fi

if [ ! -f /proc/uptime ]; then
    echo "0.00 0.00" > /proc/uptime
fi

if [ ! -f /proc/vmstat ]; then
    cat > /proc/vmstat << 'EOF'
nr_free_pages 0
nr_alloc_batch 0
nr_inactive_anon 0
nr_active_anon 0
nr_inactive_file 0
nr_active_file 0
MemTotal:     4096000 kB
MemFree:      2048000 kB
MemAvailable: 3072000 kB
EOF
fi

if [ ! -f /proc/loadavg ]; then
    echo "0.00 0.00 0.00 1/1 1" > /proc/loadavg
fi

# 2. Configure DNS
echo "[CoreCoder] Configuring DNS..."
mkdir -p /etc
cat > /etc/resolv.conf << 'EOF'
nameserver 8.8.8.8
nameserver 8.8.4.4
nameserver 1.1.1.1
EOF

# 3. Configure apt sources (Ubuntu Noble 24.04)
echo "[CoreCoder] Configuring apt sources..."
if [ -d /etc/apt ]; then
    cat > /etc/apt/sources.list.d/ubuntu.sources << 'EOF'
Types: deb
URIs: http://ports.ubuntu.com/ubuntu-ports/
Suites: noble noble-updates noble-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF

    # Remove legacy sources.list if it exists to avoid conflicts
    rm -f /etc/apt/sources.list
fi

# 4. Set up basic directory structure
echo "[CoreCoder] Setting up directories..."
mkdir -p /home
mkdir -p /root
mkdir -p /tmp

# 5. Configure locale (avoid warnings)
echo "[CoreCoder] Configuring locale..."
if [ -d /etc/default ]; then
    echo 'LANG=en_US.UTF-8' > /etc/default/locale 2>/dev/null || true
fi

# 6. Create a basic /etc/hostname
echo "corecoder" > /etc/hostname 2>/dev/null || true

# 7. Create /etc/hosts
cat > /etc/hosts << 'EOF'
127.0.0.1   localhost
::1         localhost
EOF

# 8. Set up bash prompt
if [ -f /etc/bash.bashrc ]; then
    cat >> /etc/bash.bashrc << 'EOF'

# CoreCoder prompt
export PS1='(corecoder) \w\$ '
export HOME=/home
EOF
fi

echo "[CoreCoder] Setup complete!"
