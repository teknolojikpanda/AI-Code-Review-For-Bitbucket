#!/bin/bash

# AI Code Reviewer Plugin Installation Script
# This script helps install/update the plugin in Bitbucket

PLUGIN_JAR="target/ai-code-reviewer-1.0.0-SNAPSHOT.jar"
BITBUCKET_URL="http://0.0.0.0:7990"
ADMIN_USER="admin"
ADMIN_PASS="20150467@Can"

echo "🚀 AI Code Reviewer Plugin Installation"
echo "======================================="

# Check if plugin JAR exists
if [ ! -f "$PLUGIN_JAR" ]; then
    echo "❌ Plugin JAR not found: $PLUGIN_JAR"
    echo "Please run 'mvn package' first"
    exit 1
fi

echo "✅ Plugin JAR found: $PLUGIN_JAR"

# Check if Bitbucket is running
echo "🔍 Checking Bitbucket availability..."
if ! curl -s -f "$BITBUCKET_URL" > /dev/null; then
    echo "❌ Bitbucket is not accessible at $BITBUCKET_URL"
    echo "Please ensure Bitbucket is running"
    exit 1
fi

echo "✅ Bitbucket is accessible"

echo ""
echo "📦 Plugin Information:"
echo "   - File: $PLUGIN_JAR"
echo "   - Size: $(du -h $PLUGIN_JAR | cut -f1)"
echo "   - Bitbucket: $BITBUCKET_URL"
echo ""

echo "🔧 Installation Instructions:"
echo "1. Open Bitbucket in your browser: $BITBUCKET_URL"
echo "2. Login as administrator"
echo "3. Go to Administration > Manage apps"
echo "4. Click 'Upload app'"
echo "5. Select the file: $PLUGIN_JAR"
echo "6. Click 'Upload'"
echo ""

echo "🎯 After Installation:"
echo "1. Go to Administration > AI Code Reviewer"
echo "2. Configure your Ollama settings"
echo "3. Test the connection"
echo "4. Enable the plugin"
echo ""

echo "🐛 If you encounter the 'Failed to load configuration' error:"
echo "1. Check Bitbucket logs for dependency injection errors"
echo "2. Restart Bitbucket after plugin installation"
echo "3. Verify all required dependencies are available"
echo ""

echo "✨ Installation script completed!"