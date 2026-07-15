# Folia Server Manual Installation Guide

> **⚠️ SUPERSEDED (2026-07-15) — dated setup notes, not current instructions.**
> This guide targets an older Folia version and macOS-specific install paths.
> For the current build/run procedure see [QUICKSTART.md](QUICKSTART.md); for
> current status see [PROJECT_STATUS.md](PROJECT_STATUS.md).

## Step 1: Install Java 25

Folia requires Java 25.

### Option A: Direct Download (Recommended)

1. Download from: https://adoptium.net/temurin/25/
2. Select: OpenJDK 25 LTS → macOS → x64 → JDK
3. Download the `.pkg` or `.tar.gz` file
4. Install or extract to: `/Library/Java/JavaVirtualMachines/jdk-25.jdk`

### Option B: Using SDKMAN (if available)

```bash
curl -s "https://get.sdkman.io" | bash
sdk install java 25.0.3-tem
```

## Step 2: Download Folia

1. Go to: https://papermc.io/downloads/folia
2. Download version 1.21.4 (or latest)
3. Place JAR in: `~/nebula-folia-server/`

Or use curl (if network allows):
```bash
mkdir -p ~/nebula-folia-server
cd ~/nebula-folia-server
curl -L -o folia-1.21.4.jar "https://api.papermc.io/v2/projects/folia/versions/1.21.4/builds/latest/downloads/folia-1.21.4-latest.jar"
```

## Step 3: Accept EULA

```bash
echo "eula=true" > ~/nebula-folia-server/eula.txt
```

## Step 4: Run Deployment Script

```bash
cd ~/星云架构——新一代多核优化mc服务端
./scripts/deploy-folia.sh
```

## Step 5: Start Server

```bash
cd ~/nebula-folia-server
./start.sh
```

## Alternative: Quick Start (No Script)

```bash
cd ~/nebula-folia-server
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home
$JAVA_HOME/bin/java -XX:+UseG1GC -jar folia-*.jar --nogui
```

## Verifying Installation

When server starts, you should see:
```
[Server thread/INFO]: Done (Xs)! For help, type "help"
```

## Troubleshooting

### "Java 25 not found"
- Verify JAVA_HOME points to jdk-25.jdk
- Run: `/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java -version`

### "Permission denied"
```bash
chmod +x ~/nebula-folia-server/start.sh
```

### Network issues
- Use VPN or proxy
- Download files on another machine and transfer via USB
