# Murmur/Rangzen Android App - Agent Instructions

## Deploying to ADB Test Phones

**IMPORTANT**: Always use RELEASE builds from GitHub, NOT debug builds.

Debug and release APKs have different signing keys. If you install a debug build,
the device cannot receive OTA updates (signature mismatch). This causes repeated
"package conflicts with existing package" errors.

### To deploy to ADB phones:

```bash
./scripts/deploy-adb.sh
```

This script:
1. Downloads the latest release APK from GitHub (properly signed)
2. Uninstalls any existing version (handles signature mismatches)
3. Installs the release APK on all connected devices
4. Launches the app

To deploy a specific version:
```bash
./scripts/deploy-adb.sh 0.2.64
```

### DO NOT:
- Run `./gradlew assembleDebug` and install via ADB for test devices
- This creates signature mismatches that break OTA updates

### When to use debug builds:
- Only for local development/debugging on your personal device
- When you need to attach a debugger or see debug logs

## Local Debug Testing (Development Only)

When actively debugging/developing features locally, use the debug deployment script:

```bash
./scripts/deploy-debug-local.sh --build
```

This script:
1. Builds debug APK (with --build flag, or if APK doesn't exist)
2. Uninstalls existing app on ALL connected devices
3. Installs debug APK with ALL permissions pre-granted
4. Launches the app
5. Shows peer count status after 5 seconds

**CRITICAL**: This is for LOCAL TESTING ONLY. These devices will NOT receive OTA updates
until you reinstall a release build. Use this when:
- Iterating on bug fixes locally
- Testing code changes before committing
- Debugging with logcat (Timber logs visible in debug builds)

**DO NOT** waste time manually granting permissions - the script handles everything.

## OTA Updates

OTA updates are delivered via the telemetry server to devices with QA mode enabled.
Release workflow:
1. Push a tag `v*` (e.g., `v0.2.65`)
2. GitHub Actions builds, signs, and creates a release
3. Private repo deploys APK to OTA server
4. Devices with QA mode enabled receive the update

## Building

- Debug: `./gradlew assembleDebug` (local dev only)
- Release: Done via GitHub Actions (requires signing keys in secrets)

## GitHub Repo

- Public: De-Novo-Group/rangzen-revival
- Private (OTA server): De-Novo-Group/murmur-telemetry-server

## Troubleshooting Methodology

When debugging issues, follow a rigorous scientific approach:

### 1. Document Before Acting

- Create a debug section in this file or a dedicated debug file
- Write down the problem statement clearly
- List all KNOWN FACTS (verified, not assumed)

### 2. Form Hypotheses

- List specific, testable hypotheses (H1, H2, H3...)
- Each hypothesis should be falsifiable
- Do NOT assume any hypothesis is correct until tested

### 3. Design Experiments

- For each hypothesis, define a specific test
- Define what data to collect
- Define what result would CONFIRM vs REJECT the hypothesis

### 4. Execute Systematically

- Run ONE experiment at a time
- Record ALL results, not just confirming ones
- Do NOT change direction based on partial data

### 5. Analyze Before Concluding

- Only draw conclusions after completing experiments
- State confidence level in conclusions
- Acknowledge what remains unknown

### Anti-Patterns to Avoid

- Jumping to conclusions before gathering data
- "Mystery solved!" declarations without verification
- Changing hypotheses mid-experiment
- Ignoring data that contradicts current theory
- Deploying fixes that destroy debug state (restart apps, etc.)

### Debug State Preservation

When a bug is actively occurring:

1. CAPTURE LOGS FIRST before any intervention
2. Do NOT restart apps or deploy new builds
3. Gather all diagnostic data while system is in broken state
4. Only after full capture, attempt fixes

### Create Debug Log Files

For any non-trivial investigation, create a debug log file:

```text
docs/debug/YYYY-MM-DD-<issue-name>.md
```

Structure:

```markdown
# Debug Log: <Issue Name>
Date: YYYY-MM-DD

## Problem Statement
<Precise description of what's broken, when it started, how to reproduce>

## Known Facts
- FACT 1: <verified observation with evidence>
- FACT 2: <verified observation with evidence>
(Only include verified facts, not assumptions)

## Hypotheses
- H1: <specific, testable hypothesis>
  - Test: <how to confirm or reject>
  - Prediction: <what we expect if true>
  - Status: UNTESTED / CONFIRMED / REJECTED

- H2: <another hypothesis>
  ...

## Experiment Log
### Experiment 1: <name>
- Hypothesis being tested: H1
- Procedure: <exact steps>
- Data collected: <logs, observations>
- Result: <what happened>
- Conclusion: <confirms/rejects hypothesis, or inconclusive>

### Experiment 2: <name>
...

## Current Understanding
<Summary based only on confirmed/rejected hypotheses>

## Open Questions
<What we still don't know>
```

### Following a Thread

- Once an investigation starts, COMPLETE IT before pivoting
- New observations go into the debug log, not into a new direction
- If new data seems important, add it to "Open Questions" and continue current experiment
- Only change course AFTER completing current experiment AND updating the log

### Avoiding Cognitive Traps

- **Confirmation bias**: Actively look for data that CONTRADICTS your hypothesis
- **Recency bias**: New data is not automatically more important than old data
- **Premature closure**: "Mystery solved!" requires ALL alternative hypotheses rejected
- **Action bias**: Gathering more data is often better than taking action
- **Narrative fallacy**: Don't construct stories before having sufficient facts
