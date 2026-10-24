# ENGINEERING RULES (re-read at the start of EVERY turn)

## Project
Android app (no root), applicationId com.aistudio.iossideloadbridge.kxmpzq, that sideloads an IPA onto an iPhone/iPad over USB.
Pipeline: USB permission -> libusb + embedded usbmuxd -> lockdown pairing (Trust) -> Apple ID sign-in (GrandSlam at gsa.apple.com + anisette) -> team + device (UDID) registration -> certificate (CSR) -> App ID -> provisioning profile -> zsign signing -> AFC upload + installation_proxy install -> launch.

## Known facts (from my own tests; everything else is UNKNOWN)
- Anisette client works against real servers.
- zsign engine verified on a PC with a FAKE self-signed cert only, never a real Apple cert.
- First hardware test (iPad + Android phone over USB): USB device detected (VID 0x05AC) but "claimInterface failed"; Apple sign-in failed with TLS error "Unacceptable certificate: CN=Apple Root CA...".
- The APK I tested is OLDER than the current source. Old test results do not apply to new code until I re-test.
- Earlier AI reports (including yours) claimed "100% ready / VERIFIED" without evidence and were wrong. Treat all earlier claims as UNKNOWN.

## Evidence labels (every technical claim gets one)
VERIFIED = a command you ran (show command + raw output) or log lines I pasted (quote them).
HYPOTHESIS = reasoned from code, not proven.
UNKNOWN = not checked.
"It compiles" or "tests pass" proves only that. A root cause without device logs is never VERIFIED.

## Hard rules
1. You have no iPad, phone or Apple ID. Never write logcat, stack traces, device output or test results you did not get from a command you ran or from me. Invented output is the worst possible failure.
2. Every fix: file+line, evidence for the root cause, before/after diff, and the exact log line that will prove it on my device.
3. Never weaken security: no trust-all TrustManager, no hostname-verifier bypass, no disabled certificate validation.
4. Secrets (passwords, SRP values, tokens, 2FA codes, private keys) never go into logs, files, commits or chat. Mask emails (a***@gmail.com). Redact before any export.
5. No fake progress: no delay/timer/hardcoded value may stand in for a real operation. UI status comes only from real results.
6. Minimal diffs: touch only what the evidence points to. Do not rewrite working parts (anisette client, zsign engine). No new dependency without saying why. Patches to vendored libraries must be shown as diffs.
7. If something cannot be done or tested: BLOCKED + reason. Never hide it.
8. If a step fails partway, show the failure in full. Never summarize around it or skip a failed assertion.
9. If your session resets or you are unsure what happened earlier: say so, re-read these files, run git log, rebuild context. Never present earlier output as current.
10. Do not say "ready", "done" or "100%" until the FINAL CHECKLIST is fully VERIFIED from logs I pasted.
11. Quote vendored third-party code (libusb, usbmuxd, libimobiledevice, zsign) minimally (max 25 lines per snippet; file+line for the rest). If a reply gets filtered, resend shorter with references.
12. Keep each chat reply under ~150 lines. Long material goes into docs/*.md; chat gets the summary plus key raw evidence (command output trimmed to the relevant 30 lines).
13. Do not ask me questions. Decide and record the decision in PROJECT_STATE.md. Exceptions: gate instructions, and irreversible actions (e.g. revoking an Apple certificate) which I must confirm.
14. Language requirement: pure English only across all replies, UI text, error messages, code, comments, and documentation. Instructions to user: simple plain English, maximum 5 steps.

## Every phase
START: PRE-MORTEM (5 lines): the 5 most likely ways this phase fails on real hardware, and which log line would reveal each.
END: SELF-CHECK (max 6 lines): What did I claim and which output proves each? What would a hostile reviewer call unproven? Which earlier claim does this contradict? If my fix is wrong, which log line will show it? Then relabel anything unproven.
Every phase ends with a git commit and git diff --stat.

## Turn format
First line: RULES+STATE READ | phase: X | last build id: Y
Then: (1) status (2) findings with labels (3) diffs (4) self-check (5) changed lines of PROJECT_STATE.md (6) user action (simple plain English, max 5 steps) (7) what to paste back.
