# Murmur/Rangzen Android App
#
# Run `make help` to see available commands

.PHONY: help deploy deploy-version build-debug clean

help:
	@echo "Murmur Android App - Available commands:"
	@echo ""
	@echo "  make deploy          Deploy latest RELEASE to all ADB phones"
	@echo "  make deploy-version VERSION=0.2.64"
	@echo "                       Deploy specific version to ADB phones"
	@echo ""
	@echo "  make build-debug     Build debug APK (local dev only!)"
	@echo "  make clean           Clean build artifacts"
	@echo ""
	@echo "NOTE: Always use 'make deploy' for test phones."
	@echo "      Debug builds break OTA updates due to signature mismatch."

# Deploy latest release to ADB phones (downloads from GitHub)
deploy:
	@./scripts/deploy-adb.sh

# Deploy specific version
deploy-version:
	@./scripts/deploy-adb.sh $(VERSION)

# Build debug APK (for local development only)
build-debug:
	@echo "WARNING: Debug builds cannot receive OTA updates!"
	@echo "         Use 'make deploy' for test phones instead."
	@echo ""
	@read -p "Continue with debug build? [y/N] " confirm && [ "$$confirm" = "y" ]
	./gradlew assembleDebug

clean:
	./gradlew clean
