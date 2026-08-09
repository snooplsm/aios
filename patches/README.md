# AOSP patch queue

The ideal patch queue is empty. Prefer privileged applications, product overlays,
public/system APIs, and device configuration.

When an upstream-project patch is unavoidable:

1. Add its metadata to `series.json`.
2. Store the exported patch under `patches/` and record its SHA-256 and exact
   upstream project commit.
3. Add a test that demonstrates why the patch exists.
4. Record an explicit removal condition.
5. Replay the series onto a clean upstream manifest before merging an AOSP
   update.

Never edit a synced AOSP project without either upstreaming the change or adding
it to this queue. That is the invariant that keeps AIOS updateable.

The queue tool is read-only by default:

```text
python vendor/aios/tools/verify_patch_series.py --aosp-root /absolute/aosp
```

For a build, `--apply` requires every affected project to be clean and at its
recorded commit, then applies the series to both the worktree and index. `--revert`
only removes that exact staged state and refuses unstaged edits. The lane build
script wraps both operations in a shell trap so ordinary success and failure
restore the upstream checkout:

```text
python vendor/aios/tools/verify_patch_series.py --aosp-root /absolute/aosp --apply
python vendor/aios/tools/verify_patch_series.py --aosp-root /absolute/aosp --reverse
python vendor/aios/tools/verify_patch_series.py --aosp-root /absolute/aosp --revert
```
