# Folia Server Deployment Guide

> **⚠️ SUPERSEDED (2026-07-15) — dated setup notes, not current instructions.**
> This page reflects an early macOS-oriented bring-up with a one-time Java/Folia
> download in progress. Paths, versions, and the "Current Status" list below are
> historical. For the current build/run procedure (both the plugin/agent shadow
> path and the native patched-server path) see [QUICKSTART.md](QUICKSTART.md);
> for current status see [PROJECT_STATUS.md](PROJECT_STATUS.md).

## Current Status

- ✅ Folia deployment script created: `scripts/deploy-folia.sh`
- ✅ Folia setup guide created: `docs/Folia-Server-Setup-Guide.md`
- ⏳ Java 25 downloading in background (约80分钟后完成)
- ❌ Folia JAR not yet downloaded (需要手动下载)

## Quick Setup Instructions

### 1. Install Java 25 (必需)

Download from: **https://adoptium.net/temurin/25/**

```bash
# 或手动下载 .tar.gz 然后:
tar -xzf OpenJDK25U-jdk_x64_mac_hotspot_25.0.3_9.tar.gz
sudo mv jdk-25.0.3+9 /Library/Java/JavaVirtualMachines/jdk-25.jdk
```

### 2. Download Folia

1. Visit: **https://papermc.io/downloads/folia**
2. Download version **1.21.4**
3. Save as: `~/nebula-folia-server/folia-1.21.4.jar`

Or via terminal:
```bash
mkdir -p ~/nebula-folia-server
cd ~/nebula-folia-server
curl -L -o folia-1.21.4.jar "https://api.papermc.io/v2/projects/folia/versions/1.21.4/builds/latest/downloads/folia-1.21.4-latest.jar"
```

### 3. Accept EULA
```bash
echo "eula=true" > ~/nebula-folia-server/eula.txt
```

### 4. Run Deployment
```bash
cd ~/星云架构——新一代多核优化mc服务端
./scripts/deploy-folia.sh
```

### 5. Start Server
```bash
cd ~/nebula-folia-server
./start.sh
```

## After Server Starts

### Attach async-profiler
```bash
# Find server PID
pgrep -f "folia"

# Start profiling
~/星云架构——新一代多核优化mc服务端/scripts/profiler.sh start <PID> -e cpu -i 1ms
```

### Connect to Server
- Address: `localhost:25565`
- Default: 无需外网，连接本机

## Verification Checklist

- [ ] Java 25 installed
- [ ] Folia JAR in `~/nebula-folia-server/`
- [ ] `eula.txt` created with `eula=true`
- [ ] Server starts without errors
- [ ] Can connect with Minecraft client

## Troubleshooting

### "UnsupportedClassVersionError"
→ 需要 Java 25，当前使用 Java 21

### "Permission denied"
```bash
chmod +x ~/nebula-folia-server/start.sh
```

### Network issues
→ 使用手机热点或 VPN
